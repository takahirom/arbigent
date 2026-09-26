package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentStepSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Asks Jev for the next action and takes it when Jev is confident enough; otherwise the step goes
 * to the AI. Innermost in the decision chain, so cache hits and replays never reach it and image
 * assertions still run before a goal it decides. One instance per agent: the guards below are
 * per-task state.
 */
internal class ArbigentJevDecisionInterceptor(
  private val settings: ArbigentJevSettings,
  private val client: ArbigentJevClient,
) : ArbigentDecisionInterceptor {
  private var previous: Pair<String, List<String>>? = null

  // Where focus sat for each step this interceptor saw, by step id, so the history can say "Press
  // center (focus was on X)". Index actions are named from the step's own target instead, which
  // cache hits and replays record too.
  private val actedOn = mutableMapOf<String, String>()

  // Jev steps don't count toward maxStep, so this is what stops jev from looping forever.
  private var jevStreak = 0

  override suspend fun intercept(
    decisionInput: ArbigentAi.DecisionInput,
    chain: ArbigentDecisionInterceptor.Chain,
  ): ArbigentAi.DecisionOutput {
    val tv = decisionInput.formFactor.isTv()
    val screen = decisionInput.elements.elements.map { it.index to if (tv) it.textForAI else stripBounds(it.textForAI) }
    val goal = decisionInput.contextHolder.goal
    val options = (if (tv) tvOptions(decisionInput.elements, goal) else phoneOptions(screen, goal))
      .filterValues { it.type in decisionInput.agentActionTypes }
      .mapValues { it.value.description } +
      toolOption(decisionInput.mcpTools.orEmpty())
    val request = request(goal, history(decisionInput.contextHolder), screen, if (tv) decisionInput.focusedTreeString else null, options, tv)

    if (settings.mode == ArbigentJevMode.Shadow) {
      return coroutineScope {
        // Undispatched so the request is on its way before the AI call, which blocks this thread.
        val jev = async(start = CoroutineStart.UNDISPATCHED) { askCatching(request) }
        val output = chain.proceed(decisionInput)
        val aiAction = output.agentActions.singleOrNull()
        // Never hold the AI's action for Jev: the screen can move on while waiting.
        if (jev.isCompleted) {
          jev.await()
            .onSuccess { (choice, confidence) ->
              val agreement = if (choice == aiAction?.let(::optionKey)) "agrees with" else "differs from"
              arbigentInfoLog("Jev (shadow): ${describe(choice, confidence, decisionInput)} $agreement the AI's ${aiAction?.stepLogText()}")
            }
            .onFailure { arbigentInfoLog("Jev (shadow) failed: ${it.javaClass.simpleName}: ${it.message}") }
        } else {
          jev.cancel()
          arbigentInfoLog("Jev (shadow) had not answered when the AI did")
        }
        aiAction?.let { remember(decisionInput, it) }
        output
      }
    }

    val (choice, confidence) = askCatching(request).getOrElse { e ->
      arbigentInfoLog("Jev failed, asking the AI: ${e.javaClass.simpleName}: ${e.message}")
      return proceedToAi(decisionInput, chain)
    }
    val screenKey = screen.map { it.second }
    val deferReason = when {
      // Also rules out an index that isn't on this screen: every index option comes from it.
      choice !in options -> "not one of the offered options"
      confidence.isNaN() -> "no usable confidence"
      // Jev can't fill in a tool's arguments, so it only says a tool is needed and the AI calls it.
      choice == CallToolOption -> "a tool call is left to the AI"
      choice == "goal_achieved" && confidence < (settings.goalThreshold ?: Double.POSITIVE_INFINITY) -> "goal below the goal threshold"
      confidence < settings.actionThreshold -> "below the action threshold"
      // Jev cannot see whether a scroll changed anything, so it would scroll past the target.
      choice == "scroll" -> "scroll is left to the AI"
      previous == choice to screenKey -> "same action on the same screen as the last Jev step"
      jevStreak >= MaxJevStreak -> "$MaxJevStreak Jev steps in a row"
      else -> null
    }
    if (deferReason != null) {
      arbigentInfoLog("Jev: ${describe(choice, confidence, decisionInput)}, asking the AI ($deferReason)")
      return proceedToAi(decisionInput, chain)
    }
    val action = toAction(choice)
    previous = choice to screenKey
    jevStreak++
    remember(decisionInput, action)
    arbigentInfoLog("Jev: ${describe(choice, confidence, decisionInput)}, taken without the AI")
    return ArbigentAi.DecisionOutput(
      agentActions = listOf(action),
      step = ArbigentContextHolder.Step(
        stepId = decisionInput.stepId,
        agentAction = action,
        memo = "Jev: $choice (confidence=$confidence)",
        uiTreeStrings = decisionInput.uiTreeStrings,
        cacheKey = decisionInput.cacheKey,
        screenshotFilePath = decisionInput.screenshotFilePath,
        apiCallJsonLFilePath = null,
        stepSource = ArbigentStepSource.Jev,
        countsTowardMaxStep = false,
      ),
    )
  }

  private suspend fun proceedToAi(
    decisionInput: ArbigentAi.DecisionInput,
    chain: ArbigentDecisionInterceptor.Chain,
  ): ArbigentAi.DecisionOutput {
    jevStreak = 0
    val output = chain.proceed(decisionInput)
    output.agentActions.singleOrNull()?.let { remember(decisionInput, it) }
    return output
  }

  private fun describe(choice: String, confidence: Double, decisionInput: ArbigentAi.DecisionInput): String {
    val target = Regex("""^(?:click|focus) (\d+)$""").find(choice)
      ?.let { decisionInput.elements.elements.getOrNull(it.groupValues[1].toInt()) }?.let(::nameOf)
    return "$choice${target?.let { " \"$it\"" } ?: ""} (confidence=$confidence)"
  }

  private fun remember(decisionInput: ArbigentAi.DecisionInput, action: ArbigentAgentAction) {
    if (action is DpadAutoFocusWithIndexAgentAction || action is ClickWithIndex) return
    decisionInput.elements.elements.firstOrNull { it.treeNode.focused == true }?.let(::nameOf)
      ?.let { actedOn[decisionInput.stepId] = "${action.stepLogText().removeSuffix(" 1 times")} (focus was on \"$it\")" }
  }

  // Only what was done, never the AI's memo or image description: a jev step has neither.
  private fun history(contextHolder: ArbigentContextHolder): String = contextHolder.steps()
    .mapNotNull { step ->
      step.feedback ?: actedOn[step.stepId] ?: step.agentAction?.let { describeDone(it, step.targetElement) }
    }
    .mapIndexed { i, line -> "${i + 1}. $line" }
    .joinToString("\n")
    .ifEmpty { "(none)" }

  private fun describeDone(action: ArbigentAgentAction, target: ArbigentElementIdentity?): String {
    val name = target?.let { it.text ?: it.accessibilityId }?.replace(Regex("\\s+"), " ")?.trim()?.take(80)
      ?: return action.stepLogText()
    return when (action) {
      is DpadAutoFocusWithIndexAgentAction -> "Moved focus to \"$name\" (focus only; this action did not press or select it)"
      is ClickWithIndex -> "Clicked \"$name\""
      else -> "${action.stepLogText()} (target: \"$name\")"
    }
  }

  // An option is offered only when the step lets the AI take that kind of action.
  private class Option(val type: AgentActionType, val description: String)

  private fun phoneOptions(screen: List<Pair<Int, String>>, goal: String): Map<String, Option> =
    buildMap {
      screen.forEach { (index, text) -> put("click $index", Option(ClickWithIndex, "Click element $index: ${text.take(180)}")) }
      inputCandidates(goal).forEach { put("input $it", Option(InputTextAgentAction, "Type the text \"$it\" into the focused field")) }
      put("key ENTER", Option(KeyPressAgentAction, "Press the ENTER key"))
      put("key BACK", Option(KeyPressAgentAction, "Press the BACK key"))
      put("scroll", Option(ScrollAgentAction, "Scroll down to reveal more elements"))
      put("wait", Option(WaitAgentAction, "The screen is still loading or transitioning; wait"))
      put("goal_achieved", Option(GoalAchievedAgentAction, "The goal is already achieved on the current screen; stop"))
    }

  private fun tvOptions(elements: ArbigentElementList, goal: String): Map<String, Option> = buildMap {
    elements.elements.forEach { put("focus ${it.index}", Option(DpadAutoFocusWithIndexAgentAction, "Move the D-pad focus to element ${it.index}: ${stripBounds(it.textForAI).take(160)}")) }
    put("center", Option(DpadCenterAgentAction, "Press the D-pad center key to select the currently focused element"))
    put("up", Option(DpadUpArrowAgentAction, "Press the D-pad up arrow key once"))
    put("down", Option(DpadDownArrowAgentAction, "Press the D-pad down arrow key once"))
    put("left", Option(DpadLeftArrowAgentAction, "Press the D-pad left arrow key once"))
    put("right", Option(DpadRightArrowAgentAction, "Press the D-pad right arrow key once"))
    inputCandidates(goal).forEach { put("input $it", Option(InputTextAgentAction, "Type the text \"$it\" into the focused field")) }
    put("back", Option(BackPressAgentAction, "Press the BACK key"))
    put("wait", Option(WaitAgentAction, "The screen is still loading or transitioning; wait"))
    put("goal_achieved", Option(GoalAchievedAgentAction, "The expected state is already shown on the current screen; stop"))
  }

  private fun toolOption(tools: List<MCPTool>): Map<String, String> {
    if (tools.isEmpty()) return emptyMap()
    val list = tools.joinToString("; ") { tool -> tool.name + (tool.description?.let { ": ${it.take(120)}" } ?: "") }
    return mapOf(CallToolOption to "Call one of these tools next (not a screen action): $list")
  }

  private fun request(
    goal: String,
    history: String,
    screen: List<Pair<Int, String>>,
    focusedTree: String?,
    options: Map<String, String>,
    tv: Boolean,
  ): JsonObject = buildJsonObject {
    putJsonObject("state") {
      put("goal", goal)
      put("actions_done_so_far", history)
      put("current_screen", screen.joinToString("\n") { (i, t) -> "$i: $t" })
      if (tv) put("focused_element_tree", focusedTree?.take(1500)?.ifBlank { null } ?: "(none)")
    }
    putJsonObject("questions") {
      putJsonObject("next") {
        put("type", "choice")
        put("instructions", (if (tv) "This is a TV app operated with a D-pad remote. " else "") +
          "Which single action should be performed next on current_screen to progress toward the goal?")
        putJsonObject("criteria") { options.forEach { (k, v) -> put(k, v) } }
      }
    }
  }

  // Cancellation is not a Jev failure: rethrow it, so a cancelled step never goes on to the AI.
  private suspend fun askCatching(request: JsonObject): Result<Pair<String, Double>> = try {
    Result.success(ask(request))
  } catch (e: CancellationException) {
    // Only a cancelled run stops here. A request cancelled on its own, such as a client-side
    // timeout, is just a failed answer.
    currentCoroutineContext().ensureActive()
    Result.failure(e)
  } catch (e: Exception) {
    Result.failure(e)
  }

  private suspend fun ask(request: JsonObject): Pair<String, Double> {
    val next = client.decide(request)["answers"]!!.jsonObject["next"]!!.jsonObject
    return next["choice"]!!.jsonPrimitive.content to next["confidence"]!!.jsonPrimitive.double
  }

  private fun toAction(choice: String): ArbigentAgentAction = when {
    choice.startsWith("click ") -> ClickWithIndex(choice.removePrefix("click ").toInt())
    choice.startsWith("focus ") -> DpadAutoFocusWithIndexAgentAction(choice.removePrefix("focus ").toInt())
    choice.startsWith("input ") -> InputTextAgentAction(choice.removePrefix("input "))
    choice.startsWith("key ") -> KeyPressAgentAction(choice.removePrefix("key "))
    choice == "center" -> DpadCenterAgentAction(1)
    choice == "up" -> DpadUpArrowAgentAction(1)
    choice == "down" -> DpadDownArrowAgentAction(1)
    choice == "left" -> DpadLeftArrowAgentAction(1)
    choice == "right" -> DpadRightArrowAgentAction(1)
    choice == "back" -> BackPressAgentAction()
    choice == "wait" -> WaitAgentAction(2000)
    choice == "goal_achieved" -> GoalAchievedAgentAction()
    else -> error("Jev chose an option with no action: $choice")
  }

  companion object {
    private const val MaxJevStreak = 30
    private const val CallToolOption = "call_tool"
    private val boundsRegex = Regex("""bounds=\[[^\]]*\]\[[^\]]*\], ?""")
    private val nameRegex = Regex("""(?:text|accessibilityText|content description)=([^,]*)""")
    private fun stripBounds(text: String) = text.replace(boundsRegex, "")
    private fun nameOf(element: ArbigentElement): String? =
      nameRegex.find(element.textForAI)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    // The same vocabulary as the options, so shadow mode compares like with like.
    private fun optionKey(action: ArbigentAgentAction): String = when (action) {
      is ClickWithIndex -> "click ${action.index}"
      is DpadAutoFocusWithIndexAgentAction -> "focus ${action.index}"
      is DpadCenterAgentAction -> "center"
      is DpadUpArrowAgentAction -> "up"
      is DpadDownArrowAgentAction -> "down"
      is DpadLeftArrowAgentAction -> "left"
      is DpadRightArrowAgentAction -> "right"
      is BackPressAgentAction -> "back"
      is WaitAgentAction -> "wait"
      is GoalAchievedAgentAction -> "goal_achieved"
      is KeyPressAgentAction -> "key ${action.keyName}"
      is InputTextAgentAction -> "input ${action.text}"
      else -> action.actionName
    }

    internal fun inputCandidates(goal: String): List<String> {
      val quoted = Regex("""'([^']+)'|"([^"]+)"""").findAll(goal).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
      val searchFor = Regex("""[Ss]earch for (?:the )?([A-Z][\w ]*?) in """).findAll(goal).map { it.groupValues[1] }
      return (quoted + searchFor).map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }
  }
}
