package com.github.takahirom.arbiter.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.github.takahirom.arbiter.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream

class AppStateHolder {
  class ScenarioStateHolder(initialArbiter: Arbiter) {
    // (var goal: String?, var arbiter: Arbiter?)
    val goalStateFlow: MutableStateFlow<String> = MutableStateFlow("")
    val goal get() = goalStateFlow.value
    val arbiterStateFlow: MutableStateFlow<Arbiter> = MutableStateFlow(initialArbiter)
    val isArchived = arbiterStateFlow
      .flatMapLatest { it.isArchivedStateFlow }
      .stateIn(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(),
        initialValue = false
      )
    val isRunning = arbiterStateFlow
      .flatMapLatest { it.isRunningStateFlow }
      .stateIn(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(),
        initialValue = false
      )
    val contextStateFlow = arbiterStateFlow.flatMapLatest { it.arbiterContextHolderStateFlow }
      .stateIn(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(),
        initialValue = null
      )

    fun execute() {
      arbiterStateFlow.value!!.execute(goal)
    }

    suspend fun waitUntilFinished() {
      arbiterStateFlow.value!!.waitUntilFinished()
    }

    fun isGoalAchieved(): Boolean {
      return arbiterStateFlow.value?.isArchivedStateFlow?.value ?: false
    }

    fun cancel() {
      arbiterStateFlow.value?.cancel()
    }

    fun onGoalChanged(goal: String) {
      goalStateFlow.value = goal
    }
  }

  val scenarios: MutableStateFlow<List<ScenarioStateHolder>> = MutableStateFlow(listOf())
  val selectedAgentIndex: MutableStateFlow<Int> = MutableStateFlow(0)
  val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  fun addScenario() {
    scenarios.value += ScenarioStateHolder(
      createArbiter()
    )
  }

  fun runAll() {
    coroutineScope.launch {
      scenarios.value.forEach { schenario ->
        schenario.execute()
        delay(10)
        schenario.waitUntilFinished()
      }
    }
  }

  fun runAllFailed() {
    coroutineScope.launch {
      scenarios.value.filter { scenario ->
//        scenario.contextStateFlow.value?.turns?.value?. {
//          scenario.agentCommand is GoalAchievedAgentCommand
//        } ?: true
        !scenario.isGoalAchieved()
      }.forEach { scenario: ScenarioStateHolder ->
        scenario.execute()
        delay(10)
        scenario.waitUntilFinished()
      }
    }
  }

  fun saveGoals() {
    File("goals.text").writeText(scenarios.value.map { it.goal }.joinToString("\n") { it })
  }

  fun loadGoals() {
    scenarios.value = File("goals.text").readLines().map {
      ScenarioStateHolder(
        createArbiter()
      ).apply {
        onGoalChanged(it)
      }
    }
  }
}

private fun createArbiter() = arbiter {
  ai(OpenAIAi(System.getenv("API_KEY")!!))
  device(MaestroDevice(maestroInstance))
}

@Composable
@Preview
fun App() {
  MaterialTheme {
    val appStateHolder = remember { AppStateHolder() }
    Row {
      val schenarios by appStateHolder.scenarios.collectAsState()
      val coroutineScope = rememberCoroutineScope()
      Column(
        Modifier
          .weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Button(onClick = {
          appStateHolder.addScenario()
        }) {
          Row(
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.Add,
              contentDescription = "Add scenario",
              tint = Color.White
            )
            Text("Add scenario")
          }
        }
        Button(onClick = {
          appStateHolder.runAll()
        }) {
          Row(
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.PlayArrow,
              contentDescription = "Run all scenarios",
              tint = Color.Green
            )
            Text("Run all scenarios")
          }
        }

        Button(onClick = {
          coroutineScope.launch {
            appStateHolder.runAllFailed()
          }
        }) {
          Row(
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.PlayArrow,
              contentDescription = "Run all failed scenarios",
              tint = Color.Green
            )
            Icon(
              imageVector = Icons.Default.Warning,
              contentDescription = "Run all failed scenario",
              tint = Color.Yellow
            )
            Text("Run failed scenarios")
          }
        }
        Button(onClick = {
          appStateHolder.saveGoals()
        }) {
          Row(
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.Edit,
              contentDescription = "Save all scenarios",
              tint = Color.White
            )
            Text("Save all scenarios")
          }
        }
        Button(onClick = {
          appStateHolder.loadGoals()
        }) {
          Row(
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.Edit,
              contentDescription = "Load all scenarios",
              tint = Color.White
            )
            Text("Load all scenarios")
          }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
          itemsIndexed(schenarios) { index, scenarioStateHolder ->
            val goal by scenarioStateHolder.goalStateFlow.collectAsState()
            Card(
              modifier = Modifier.fillMaxWidth().padding(8.dp)
                .clickable { appStateHolder.selectedAgentIndex.value = index },
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  modifier = Modifier.weight(1f),
                  text = "Goal:" + goal
                )
                val isArchived by scenarioStateHolder.isArchived.collectAsState()
                println("Compose isArchived: $isArchived")
                if (isArchived) {
                  Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Archived",
                    tint = Color.White,
                    modifier = Modifier.padding(8.dp)
                      .size(40.dp)
                      .clip(
                        CircleShape
                      )
                      .background(Color.Green)
                  )
                }
                val isRunning by scenarioStateHolder.isRunning.collectAsState()
                if (isRunning) {
                  CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp)
                  )
                }
              }
            }
          }
        }
      }
      val index by appStateHolder.selectedAgentIndex.collectAsState()
      val scenarioStateHolder = schenarios.getOrNull(index)
      if (scenarioStateHolder != null) {
        Column(Modifier.weight(3f)) {
          Agent(scenarioStateHolder)
        }
      }
    }
  }
}

@Composable
private fun Agent(scenarioStateHolder: AppStateHolder.ScenarioStateHolder) {
  val isAndroid by remember { mutableStateOf(true) }
  val arbiter: Arbiter? by scenarioStateHolder.arbiterStateFlow.collectAsState()
  if (arbiter == null) {
    return
  }
  val agentArbiterContextHolder by arbiter!!.arbiterContextHolderStateFlow.collectAsState()
  val goal by scenarioStateHolder.goalStateFlow.collectAsState()
  var editingGoalTextState by remember(goal) { mutableStateOf(goal) }
  Column {
    Row(
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.padding(8.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = isAndroid,
            onClick = { }
          )
          Text("Android")
        }
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = !isAndroid,
            onClick = { }
          )
          Text("iOS")
        }
      }
      Column(modifier = Modifier.padding(8.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = isAndroid,
            onClick = { }
          )
          Text("Mobile")
        }
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = !isAndroid,
            onClick = { }
          )
          Text("TV")
        }
      }
      Text("Goal:")
      TextField(
        modifier = Modifier.weight(1f),
        value = editingGoalTextState,
        onValueChange = {
          editingGoalTextState = it
          scenarioStateHolder.onGoalChanged(it)
        },
      )
      Button(onClick = {
        scenarioStateHolder.execute()
      }) {
        Text("Run")
      }
      Button(onClick = {
        scenarioStateHolder.cancel()
      }) {
        Text("Cancel")
      }
    }
    agentArbiterContextHolder?.let { agentContext ->
      var selectedIndex: Int? by remember(agentContext.turns.value.size) { mutableStateOf(null) }
      val turns by agentContext.turns.collectAsState()
      Row(Modifier.weight(1f)) {
        LazyColumn(modifier = Modifier.weight(1f)) {
          items(turns.size) { index ->
            val turn = turns[index]
            Card(
              modifier = Modifier.fillMaxWidth().padding(8.dp)
                .clickable { selectedIndex = index },
              backgroundColor = if (turn.memo.contains("Failed")) {
                MaterialTheme.colors.error
              } else {
                MaterialTheme.colors.surface
              }
            ) {
              Text(
                modifier = Modifier.padding(8.dp),
                text = turn.text()
              )
            }
          }
        }
        selectedIndex?.let { selectedIndex ->
          val turn = turns[selectedIndex]
          turn.message?.let { message: String ->
            val scrollableState = rememberScrollState()
            Card(
              Modifier.weight(2F)
                .padding(8.dp),
            ) {
              Text(
                modifier = Modifier.verticalScroll(scrollableState),
                text = message
              )
            }
          }
          turn.screenshotFileName.let {
            Image(
              bitmap = loadImageBitmap(FileInputStream("screenshots/" + it + ".png")),
              contentDescription = "screenshot",
              modifier = Modifier.weight(2F)
            )
          }
        }
      }
    }
  }
}

fun main() = application {
  Window(
    title = "App Test AI Agent",
    onCloseRequest = {
      closeMaestro()
      exitApplication()
    }) {
    App()
  }
}
