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
import com.github.takahirom.ai_agent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileInputStream

class AppStateHolder {
  val agents: MutableStateFlow<List<AgentStateHolder>> = MutableStateFlow<List<AgentStateHolder>>(listOf())
  val selectedAgentIndex: MutableStateFlow<Int> = MutableStateFlow(0)
  val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  fun runAll() {
    coroutineScope.launch {
      agents.value.forEach {
        it.run(
          it.goal.value
        )
        delay(10)
        it.isRunning.filter { !it }.first()
      }
    }
  }

  fun runAllFailed() {
    coroutineScope.launch {
      agents.value.filter {
//        it.contextStateFlow.value?.turns?.value?. {
//          it.agentCommand is GoalAchievedAgentCommand
//        } ?: true
        !(it.contextStateFlow.value?.turns?.value?.any { it.agentCommand is GoalAchievedAgentCommand } ?: false)
      }.forEach {
        it.run(
          it.goal.value
        )
        delay(10)
        it.isRunning.filter { !it }.first()
      }
    }
  }

  fun saveGoals() {
    File("goals.text").writeText(agents.value.joinToString("\n") { it.goal.value })
  }

  fun loadGoals() {
    agents.value = File("goals.text").readLines().map { AgentStateHolder(initialGoal = it) }
  }
}

@Composable
@Preview
fun App() {
  MaterialTheme {
    val appStateHolder = remember { AppStateHolder() }
    Row {
      val agents by appStateHolder.agents.collectAsState()
      val coroutineScope = rememberCoroutineScope()
      Column(
        Modifier
          .weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Button(onClick = {
          appStateHolder.agents.value += AgentStateHolder()
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
          itemsIndexed(agents) { index, agentStateHolder ->
            val goal by agentStateHolder.goal.collectAsState()
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
                val isArchived by agentStateHolder.isArchived.collectAsState()
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
                val isRunning by agentStateHolder.isRunning.collectAsState()
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
      val agentStateHolder = agents.getOrNull(index)
      if (agentStateHolder != null) {
        Column(Modifier.weight(3f)) {
          Agent(agentStateHolder)
        }
      }
    }
  }
}

@Composable
private fun Agent(agentStateHolder: AgentStateHolder) {
  val isAndroid by remember { mutableStateOf(true) }
  val agentContext: Context? by agentStateHolder.contextStateFlow.collectAsState()
  val goal by agentStateHolder.goal.collectAsState()
  var editinggoalTextState by remember(goal) { mutableStateOf(goal) }
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
        value = editinggoalTextState,
        onValueChange = {
          editinggoalTextState = it
        },
      )
      Button(onClick = {
        agentStateHolder.run(editinggoalTextState)
      }) {
        Text("Run")
      }
      Button(onClick = {
        agentStateHolder.cancel()
      }) {
        Text("Cancel")
      }
    }
    agentContext?.let { agentContext ->
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
          val scrollableState = rememberScrollState()
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
