import androidx.compose.runtime.*
import io.github.takahirom.arbigent.result.*
import kotlinx.serialization.decodeFromString
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposableInBody
import org.w3c.dom.HTMLDivElement

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
      display(DisplayStyle.Flex) // Use Flexbox for layout
      flexDirection(FlexDirection.Row)
    }
  }) {
    var selectedScenario by remember { mutableStateOf<ArbigentScenarioResult?>(null) }

    Div(
      {
        style {
          display(DisplayStyle.Flex)
          flexDirection(FlexDirection.Column)
          width(200.px)
          padding(10.px)
        }
      }
    ) {
      val startTimestamp = result.startTimestamp()
      val endTimestamp = result.endTimestamp()
      if (startTimestamp != null && endTimestamp != null) {
        Div {
          Text("Duration: ${(endTimestamp.toDouble() - startTimestamp) / 1000}s")
        }
      }
      Div {
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
        padding(5.px)
        cursor("pointer") // Change cursor to pointer on hover
        border {
          right(1.px)
          style(LineStyle.Solid)
          color(Color.gray)
        }
        if (scenario == selectedScenario) {
          backgroundColor(Color.lightgray)
        }
//        hover {
//          backgroundColor(Color.lightgray)
//        }
      }
      onClick {
        onScenarioSelected(scenario)
      }
    }) {
      Text("scenario: ${scenario.goal ?: scenario.id}")
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
        padding(10.px)
      }
    }
  ) {
    Div {
      Text("Goal: ${scenario.goal ?: "N/A"}")
    }
    Div {
      Text("Status: ${scenario.executionStatus ?: "N/A"}")
    }
    Div {
      Text("Success: ${scenario.isSuccess}")
    }
    val startTimestamp = scenario.startTimestamp()
    val endTimestamp = scenario.endTimestamp()
    if (startTimestamp != null && endTimestamp != null) {
      Div {
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
        marginTop(10.px)
      }
    }
  ) {
    Div {
      Text("Retry History Status: ${agentResults.status}")
    }
    val startTimestamp = agentResults.startTimestamp()
    val endTimestamp = agentResults.endTimestamp()
    if (startTimestamp != null && endTimestamp != null) {
      Div {
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
      border(1.px, LineStyle.Solid, Color.gray)
      padding(5.px)
      marginTop(5.px)
    }
  }) {
    Div {
      Text("Task($taskIndex) Goal: ${agentResult.goal}")
    }
    Div {
      Text("Max Steps: ${agentResult.maxStep}")
    }
    Div {
      Text("Device(Form Factor): ${agentResult.deviceName}(${agentResult.deviceFormFactor})")
    }
    Div {
      Text("Goal Achieved: ${agentResult.isGoalAchieved}")
    }
    val startTimestamp = agentResult.startTimestamp
    val endTimestamp = agentResult.endTimestamp
    if (startTimestamp != null && endTimestamp != null) {
      Div {
        Text("Duration: ${(endTimestamp.toDouble() - startTimestamp) / 1000}s")
      }
    }

    agentResult.steps.forEachIndexed { index, step ->
      Div {
        Text("Step(${(index + 1)}/${agentResult.steps.size})")
      }
      StepView(step)
    }
  }
}

@Composable
private fun StepView(step: ArbigentAgentTaskStepResult) {
  Div({
    style {
      display(DisplayStyle.Flex)
      flexDirection(FlexDirection.Row)
      marginTop(5.px)
    }
  }) {
    Div(
      {
        style {
          display(DisplayStyle.Flex)
          flexDirection(FlexDirection.Column)
          flexGrow(1)
        }
      }
    ) {
      if (step.cacheHit) {
        Pre({
          style {
            whiteSpace("pre-wrap")
            backgroundColor(Color.lightgreen)
          }
        }) {
          Text("Cache Hit")
        }
      }
      Pre({
        style {
          whiteSpace("pre-wrap")
        }
      }) {
        Text(step.summary)
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

      ExpandableSection("AI Request") {
        Pre({
          style {
            whiteSpace("pre-wrap")
          }
        }) {
          Text(step.aiRequest?.replace("<", "&lt;")?.replace(">", "&gt;") ?: "N/A")
        }
      }
      ExpandableSection("AI Response") {
        Pre({
          style {
            whiteSpace("pre-wrap")
          }
        }) {
          Text(step.aiResponse ?: "N/A")
        }
      }
    }
    Div({
      style {
        width(40.percent)
        minWidth(20.percent)
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        alignItems(AlignItems.Center)
      }
    }) {
      if (step.screenshotFilePath.isNotEmpty()) {
        ExpandableSection("Annotated Screenshot", defaultExpanded = !step.cacheHit) {
          AsyncImage(
            path = step.screenshotFilePath.substringBeforeLast(".") + "_annotated." + step.screenshotFilePath.substringAfterLast(
              "."
            ),
            contentDescription = "Annotated Screenshot for step: ${step.summary}"
          )
        }
        ExpandableSection("Screenshot", defaultExpanded = step.cacheHit) {
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
        marginBottom(10.px)
      }
    }
  ) {
    Div({
      style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Row)
        justifyContent(JustifyContent.SpaceBetween)
        cursor("pointer")
        padding(5.px)
        backgroundColor(Color.lightgray)
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
//        objectFit(ObjectFit.Contain) // Maintain aspect ratio while fitting within bounds
      }
    }
  )
}
