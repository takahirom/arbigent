import androidx.compose.runtime.*
import io.github.takahirom.arbigent.result.*
import kotlinx.serialization.decodeFromString
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposableInBody

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
      ScenarioList(result.scenarios) { scenario ->
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

    scenario.histories.forEach { agentResults ->
      AgentResultsView(agentResults)
    }
  }
}

@Composable
private fun AgentResultsView(agentResults: ArbigentAgentResults) {
  Div {
    Text("Agent Status: ${agentResults.status}")
    agentResults.agentResult.forEachIndexed { taskIndex, agentResult ->
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
      Text("Device Form Factor: ${agentResult.deviceFormFactor}")
    }
    Div {
      Text("Goal Archived: ${agentResult.isGoalArchived}")
    }

    agentResult.steps.forEach { step ->
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
    Pre({
      style {
        flexGrow(1)
        whiteSpace("pre-wrap")
      }
    }) {
      Text("Summary: ${step.summary}")
    }

    if (step.screenshotFilePath.isNotEmpty()) {
      Div({
        style {
          width(40.percent)
          minWidth(20.percent)
          display(DisplayStyle.Flex)
          justifyContent(JustifyContent.Center)
        }
      }) {
        AsyncImage(
          path = step.screenshotFilePath,
          contentDescription = "Screenshot for step: ${step.summary}"
        )
      }
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
