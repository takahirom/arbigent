import io.github.takahirom.arbigent.result.ArbigentStepSource
import androidx.compose.runtime.*
import io.github.takahirom.arbigent.result.*
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposableInBody
import org.w3c.dom.HTMLDivElement

private val reportText = Color("#1e293b")
private val reportMuted = Color("#64748b")
private val reportBorder = Color("#e2e8f0")
private val reportAccent = Color("#2563eb")
private val reportSuccess = Color("#15803d")
private val reportFailure = Color("#b91c1c")

private fun StyleScope.heading(size: Int) {
  fontSize(size.px)
  fontWeight(600)
  color(reportText)
  property("line-height", "1.4")
  marginBottom(8.px)
}

private fun StyleScope.metadata() {
  fontSize(13.px)
  color(reportMuted)
  marginBottom(4.px)
}

private fun StyleScope.outcome(success: Boolean) {
  fontWeight(600)
  color(if (success) reportSuccess else reportFailure)
}

private fun StyleScope.badge() {
  alignSelf(AlignSelf.FlexStart)
  margin(0.px, 0.px, 8.px)
  padding(2.px, 8.px)
  borderRadius(6.px)
  fontFamily("inherit")
  fontSize(12.px)
  fontWeight(600)
  whiteSpace("pre-wrap")
  color(Color("#475569"))
  backgroundColor(Color("#f1f5f9"))
  border(1.px, LineStyle.Solid, reportBorder)
}

@JsExport
public abstract class ArbigentReportAppController {
  public abstract fun dispose()
}

@JsExport
public fun ArbigentReportApp(reportString: String): ArbigentReportAppController {
  val composition = renderComposableInBody {
    ArbigentReportComposeApp(reportString)
  }
  return object : ArbigentReportAppController() {
    override fun dispose() {
      composition.dispose()
    }
  }
}

@Composable
private fun ArbigentReportComposeApp(reportString: String) {
  val result = remember {
    ArbigentProjectExecutionResult.yaml.decodeFromString<ArbigentProjectExecutionResult>(reportString)
  }

  Div({
    style {
      display(DisplayStyle.Flex)
      flexDirection(FlexDirection.Row)
      fontFamily("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "sans-serif")
      fontSize(14.px)
      property("line-height", "1.6")
      property("overflow-wrap", "anywhere")
      color(reportText)
      backgroundColor(Color("#f8fafc"))
      minHeight(100.vh)
    }
  }) {
    var selectedScenario by remember { mutableStateOf<ArbigentScenarioResult?>(null) }

    Div(
      {
        style {
          display(DisplayStyle.Flex)
          flexDirection(FlexDirection.Column)
          width(300.px)
          minWidth(300.px)
          flexShrink(0)
          padding(24.px, 16.px)
          property("box-sizing", "border-box")
          property("border-right", "1px solid #e2e8f0")
        }
      }
    ) {
      val startTimestamp = result.startTimestamp()
      val endTimestamp = result.endTimestamp()
      if (startTimestamp != null && endTimestamp != null) {
        Div({ style { metadata(); marginBottom(16.px) } }) {
          Text("Duration: ${(endTimestamp.toDouble() - startTimestamp) / 1000}s")
        }
      }
      Div({ style { heading(16); marginBottom(16.px) } }) {
        Text("Scenarios")
      }
      ScenarioList(result.scenarios, selectedScenario) { scenario ->
        selectedScenario = scenario
      }
    }

    selectedScenario?.let { scenario ->
      ScenarioDetails(scenario)
    }
  }
}

@Composable
private fun ScenarioList(
  scenarios: List<ArbigentScenarioResult>,
  selectedScenario: ArbigentScenarioResult?,
  onScenarioSelected: (ArbigentScenarioResult) -> Unit
) {
  scenarios.forEach { scenario ->
    Div({
      style {
        padding(14.px)
        marginBottom(10.px)
        cursor("pointer")
        border(1.px, LineStyle.Solid, reportBorder)
        borderRadius(10.px)
        backgroundColor(Color.white)
        if (scenario == selectedScenario) {
          backgroundColor(Color("#eff6ff"))
          border(1.px, LineStyle.Solid, reportAccent)
        }
      }
      onClick {
        onScenarioSelected(scenario)
      }
    }) {
      Div({
        style {
          heading(14)
        }
      }) {
        Text("${scenario.goal ?: scenario.id}")
      }
      Div({
        style {
          fontSize(12.px)
          marginBottom(2.px)
          color(reportMuted)
        }
      }) {
        Text("Status: ${scenario.executionStatus ?: "N/A"}")
      }
      Div({
        style {
          fontSize(12.px)
          marginBottom(2.px)
          outcome(scenario.isSuccess)
        }
      }) {
        Text("Success: ${scenario.isSuccess}")
      }
      val startTimestamp = scenario.startTimestamp()
      val endTimestamp = scenario.endTimestamp()
      if (startTimestamp != null && endTimestamp != null) {
        Div({
          style {
            fontSize(12.px)
            color(reportMuted)
          }
        }) {
          Text("Duration: ${(endTimestamp.toDouble() - startTimestamp) / 1000}s")
        }
      }
    }
  }
}

@Composable
private fun ScenarioDetails(scenario: ArbigentScenarioResult) {
  Div(
    {
      style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        flexGrow(1)
        minWidth(0.px)
        padding(28.px, 32.px)
        backgroundColor(Color.white)
      }
    }
  ) {
    Div({ style { heading(26); marginBottom(16.px) } }) {
      Text("Goal: ${scenario.goal ?: "N/A"}")
    }
    Div({ style { metadata() } }) {
      Text("Status: ${scenario.executionStatus ?: "N/A"}")
    }
    Div({ style { outcome(scenario.isSuccess); marginBottom(4.px) } }) {
      Text("Success: ${scenario.isSuccess}")
    }
    val startTimestamp = scenario.startTimestamp()
    val endTimestamp = scenario.endTimestamp()
    if (startTimestamp != null && endTimestamp != null) {
      Div({ style { metadata() } }) {
        Text("Duration: ${(endTimestamp.toDouble() - startTimestamp) / 1000}s")
      }
    }

    scenario.histories.forEach { agentResults ->
      AgentResultsView(agentResults)
    }
  }
}

@Composable
private fun AgentResultsView(agentResults: ArbigentAgentResults) {
  Div(
    {
      style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        marginTop(32.px)
        paddingTop(24.px)
        property("border-top", "1px solid #e2e8f0")
      }
    }
  ) {
    Div({ style { heading(20) } }) {
      Text("Retry History Status: ${agentResults.status}")
    }
    val startTimestamp = agentResults.startTimestamp()
    val endTimestamp = agentResults.endTimestamp()
    if (startTimestamp != null && endTimestamp != null) {
      Div({ style { metadata() } }) {
        Text("Duration: ${(endTimestamp.toDouble() - startTimestamp) / 1000}s")
      }
    }
    agentResults.agentResults.forEachIndexed { taskIndex, agentResult ->
      AgentResultView(taskIndex, agentResult)
    }
  }
}

@Composable
private fun AgentResultView(taskIndex: Int, agentResult: ArbigentAgentResult) {
  Div({
    style {
      display(DisplayStyle.Flex)
      flexDirection(FlexDirection.Column)
      border(1.px, LineStyle.Solid, reportBorder)
      borderRadius(12.px)
      padding(20.px)
      marginTop(16.px)
    }
  }) {
    Div({ style { heading(17); marginBottom(12.px) } }) {
      Text("Task($taskIndex) Goal: ${agentResult.goal}")
    }
    agentResult.callBreadcrumb?.let { breadcrumb ->
      Div({ style { metadata() } }) {
        Text("Called via: $breadcrumb")
      }
    }
    Div({ style { metadata() } }) {
      Text("Max Steps: ${agentResult.maxStep}")
    }
    Div({ style { metadata() } }) {
      Text("Device(Form Factor): ${agentResult.deviceName}(${agentResult.deviceFormFactor})")
    }
    Div({ style { outcome(agentResult.isGoalAchieved); marginBottom(4.px) } }) {
      Text("Goal Achieved: ${agentResult.isGoalAchieved}")
    }
    val startTimestamp = agentResult.startTimestamp
    val endTimestamp = agentResult.endTimestamp
    if (startTimestamp != null && endTimestamp != null) {
      Div({ style { metadata() } }) {
        Text("Duration: ${(endTimestamp.toDouble() - startTimestamp) / 1000}s")
      }
    }

    agentResult.steps.forEachIndexed { index, step ->
      Div({
        style {
          heading(14)
          marginTop(24.px)
          paddingTop(16.px)
          property("border-top", "1px solid #e2e8f0")
        }
      }) {
        Text("Step(${(index + 1)}/${agentResult.steps.size})")
      }
      StepView(step)
    }
  }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun formatTimestamp(timestamp: Long): String {
  val instant = Instant.fromEpochMilliseconds(timestamp)
  return instant.toString().replace('T', ' ').substringBefore('.')
}

@Composable
private fun StepView(step: ArbigentAgentTaskStepResult) {
  Div({
    style {
      display(DisplayStyle.Flex)
      flexDirection(FlexDirection.Row)
      marginTop(8.px)
      property("gap", "20px")
    }
  }) {
    Div(
      {
        style {
          display(DisplayStyle.Flex)
          flexDirection(FlexDirection.Column)
          flexGrow(1)
          property("flex-basis", "0")
          minWidth(0.px)
        }
      }
    ) {
      if (step.stepSource != ArbigentStepSource.Ai) {
        Pre({
          style {
            badge()
          }
        }) {
          Text(
            when (step.stepSource) {
              ArbigentStepSource.Cache -> "Cache Hit"
              ArbigentStepSource.Replay -> "Replayed"
              ArbigentStepSource.Ai -> ""
            }
          )
        }
      }
      if (step.agentAction?.contains("MCP") == true) {
        Pre({
          style {
            badge()
            color(Color("#1d4ed8"))
            backgroundColor(Color("#eff6ff"))
          }
        }) {
          Text("MCP")
        }
      }
      Pre({
        style {
          whiteSpace("pre-wrap")
          fontFamily("inherit")
          fontSize(14.px)
          margin(0.px, 0.px, 12.px)
        }
      }) {
        Text("${step.summary} (Time: ${formatTimestamp(step.timestamp)})")
      }
//      ExpandableSection("UI Tree Strings") {
//        Pre({
//          style {
//            whiteSpace("pre-wrap")
//          }
//        }) {
//          Text("All Tree String: ${step.uiTreeStrings?.allTreeString ?: "N/A"}")
//          Text("Optimized Tree String: ${step.uiTreeStrings?.optimizedTreeString ?: "N/A"}")
//        }
//      }

      if (step.apiCallJsonPath != null) {
        Div({
          style {
            marginBottom(10.px)
          }
        }) {
          if (step.stepSource == ArbigentStepSource.Ai) {
            A(
              href = step.apiCallJsonPath,
              attrs = {
                attr("target", "_blank")
                attr("rel", "noopener noreferrer")
                style {
                  color(reportAccent)
                  fontSize(12.px)
                  textDecoration("underline")
                }
              }
            ) {
              Text("View AI Request/Response (JSONL): ${step.apiCallJsonPath}")
            }
          } else {
            Div {
              Text(
                "AI Request/Response (JSONL): ${step.apiCallJsonPath} " +
                  when (step.stepSource) {
                    ArbigentStepSource.Replay -> "(Replayed)"
                    else -> "(Cache Hit)"
                  }
              )
            }
          }
        }
      }
    }
    Div({
      style {
        width(40.percent)
        minWidth(0.px)
        flexShrink(0)
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        alignItems(AlignItems.Stretch)
      }
    }) {
      if (step.screenshotFilePath.isNotEmpty()) {
        val isAnnotatedExpandDefault = !step.summary.contains("Image assertion", ignoreCase = true)
          && step.stepSource == ArbigentStepSource.Ai
        ExpandableSection("Annotated Screenshot", defaultExpanded = isAnnotatedExpandDefault) {
          AsyncImage(
            path = step.screenshotFilePath.substringBeforeLast(".") + "_annotated." + step.screenshotFilePath.substringAfterLast(
              "."
            ),
            contentDescription = "Annotated Screenshot for step: ${step.summary}"
          )
        }
        ExpandableSection("Screenshot", defaultExpanded = !isAnnotatedExpandDefault) {
          AsyncImage(
            path = step.screenshotFilePath,
            contentDescription = "Screenshot for step: ${step.summary}"
          )
        }
      }
    }
  }
}

@Composable
public fun ExpandableSection(
  title: String,
  attrs: AttrBuilderContext<HTMLDivElement>? = null,
  defaultExpanded: Boolean = false,
  content: @Composable () -> Unit
) {
  var expanded by remember { mutableStateOf(defaultExpanded) }
  Div(
    {
      attrs?.invoke(this)
      style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        marginBottom(12.px)
        minWidth(0.px)
        property("gap", "8px")
      }
    }
  ) {
    Div({
      style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Row)
        alignItems(AlignItems.Center)
        alignSelf(AlignSelf.FlexStart)
        property("gap", "8px")
        cursor("pointer")
        padding(4.px, 8.px)
        fontSize(12.px)
        fontWeight(500)
        property("line-height", "1.4")
        color(reportMuted)
        backgroundColor(Color("#f8fafc"))
        border(1.px, LineStyle.Solid, reportBorder)
        borderRadius(6.px)
      }
      onClick {
        expanded = !expanded
      }
    }) {
      Text(title)
      Text(if (expanded) "-" else "+")
    }
    if (expanded) {
      content()
    }
  }
}

@Composable
public fun AsyncImage(
  path: String,
  contentDescription: String
) {
  Img(
    src = path,
    alt = contentDescription,
    attrs = {
      style {
        maxWidth(100.percent)
        maxHeight(400.px)
        alignSelf(AlignSelf.FlexStart)
        property("object-fit", "contain")
        borderRadius(6.px)
      }
    }
  )
}
