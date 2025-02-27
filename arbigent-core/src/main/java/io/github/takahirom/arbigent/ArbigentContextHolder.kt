package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentAgentTaskStepResult
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

public class ArbigentContextHolder(
  public val goal: String,
  public val maxStep: Int,
  public val startTimestamp: Long = System.currentTimeMillis(),
  private val userPromptTemplate: UserPromptTemplate = UserPromptTemplate(UserPromptTemplate.DEFAULT_TEMPLATE),
) {
  public fun generateStepId(): String {
    return "" + goal.hashCode() + "_" +
      steps().size +"_" + startTimestamp + "_" + System.currentTimeMillis() .toString()
  }
  @Serializable
  public data class Step(
    public val stepId: String,
    public val agentCommand: ArbigentAgentCommand? = null,
    public val action: String? = null,
    public val feedback: String? = null,
    public val memo: String? = null,
    public val imageDescription: String? = null,
    public val uiTreeStrings: ArbigentUiTreeStrings? = null,
    public val aiRequest: String? = null,
    public val aiResponse: String? = null,
    public val cacheKey: String,
    public val timestamp: Long = System.currentTimeMillis(),
    public val screenshotFilePath: String,
    public val apiCallJsonLFilePath: String? = null,
    public val cacheHit: Boolean = false,
  ) {
    public fun isFailed(): Boolean {
      return feedback?.contains("Failed") == true
    }

    public fun text(): String {
      return buildString {
        imageDescription?.let { append("image description: $it\n") }
        memo?.let { append("memo: $it\n") }
        feedback?.let { append("feedback: $it\n") }
        action?.let { append("action: $it\n") }
        agentCommand?.let { append("action done: ${it.stepLogText()}\n") }
      }
    }

    public fun getResult(): ArbigentAgentTaskStepResult {
      return ArbigentAgentTaskStepResult(
        stepId = stepId,
        summary = text(),
        timestamp = timestamp,
        screenshotFilePath = screenshotFilePath,
        apiCallJsonPath = apiCallJsonLFilePath,
        agentCommand = agentCommand?.stepLogText(),
//        uiTreeStrings = uiTreeStrings,
        aiRequest = aiRequest,
        aiResponse = aiResponse,
        cacheHit = cacheHit
      )
    }
  }

  private val _steps = MutableStateFlow<List<Step>>(listOf())
  public val stepsFlow: Flow<List<Step>> = _steps.asSharedFlow()
  public fun isGoalAchieved(): Boolean =
    steps().any { it.agentCommand is GoalAchievedAgentCommand }

  public fun steps(): List<Step> = _steps.value
  public fun addStep(step: Step) {
    _steps.value = steps().toMutableList() + step
  }

  public fun getStepsText(): String {
    return steps().mapIndexed { index, turn ->
      "Step ${index + 1}. \n" + turn.text()
    }.joinToString("\n")
  }

  public fun prompt(
    uiElements: String,
    focusedTree: String,
    commandTemplates: String
  ): String {
    return userPromptTemplate.format(
      goal = goal,
      currentStep = steps().size + 1,
      maxStep = maxStep,
      steps = getStepsText(),
      uiElements = uiElements,
      focusedTree = focusedTree,
      commandTemplates = commandTemplates
    )
  }

  public fun context(): String {
    return userPromptTemplate.format(
      goal = goal,
      currentStep = steps().size + 1,
      maxStep = maxStep,
      steps = getStepsText()
    )
  }
}
