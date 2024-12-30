package com.github.takahirom.arbiter.ui

import androidx.compose.foundation.text.input.TextFieldState
import com.github.takahirom.arbiter.Ai
import com.github.takahirom.arbiter.Arbiter
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.ArbiterInitializerInterceptor
import com.github.takahirom.arbiter.Device
import com.github.takahirom.arbiter.InputCommandType
import com.github.takahirom.arbiter.RunningInfo
import com.github.takahirom.arbiter.AgentConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand

@Serializable
sealed interface ClearData {
  @Serializable
  @SerialName("Noop")
  data object Noop : ClearData

  @Serializable
  @SerialName("ClearData")
  data class Clear(val packageName: String) : ClearData
}

@Serializable
sealed interface InitializeMethods {
  @Serializable
  @SerialName("Back")
  data object Back : InitializeMethods

  @Serializable
  @SerialName("Noop")
  data object Noop : InitializeMethods

  @Serializable
  @SerialName("OpenApp")
  data class OpenApp(val packageName: String) : InitializeMethods
}


class ScenarioStateHolder(val device: Device, val ai: Ai) {
  val goalState = TextFieldState("")
  val goal get() = goalState.text.toString()
  val maxRetryState: TextFieldState = TextFieldState("3")
  val maxTurnState: TextFieldState = TextFieldState("10")
  val initializeMethodsStateFlow: MutableStateFlow<InitializeMethods> =
    MutableStateFlow(InitializeMethods.Back)
  val inputCommandTypeStateFlow: MutableStateFlow<InputCommandType> =
    MutableStateFlow(InputCommandType.Mobile)
  val dependencyScenarioStateFlow = MutableStateFlow<ScenarioStateHolder?>(null)
  val arbiterStateFlow = MutableStateFlow<Arbiter?>(null)
  private val coroutineScope = CoroutineScope(
    ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob()
  )
  val isArchived = arbiterStateFlow
    .flatMapLatest { it?.isArchivedStateFlow ?: flowOf() }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = false
    )
  val isRunning = arbiterStateFlow
    .flatMapLatest { it?.isRunningStateFlow ?: flowOf() }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = false
    )

  val runningInfo: StateFlow<RunningInfo?> = arbiterStateFlow
    .flatMapLatest { it?.runningInfoStateFlow ?: flowOf() }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = null
    )

  suspend fun onExecute(scenario: Arbiter.Scenario) {
    arbiterStateFlow.value?.cancel()
    val arbiter = Arbiter {
    }
    arbiterStateFlow.value = arbiter
    arbiterStateFlow.value!!.execute(
      scenario,
    )
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
    goalState.edit {
      replace(0, goalState.text.length, goal)
    }
  }

  fun createAgentConfig(device: Device, ai: Ai) = AgentConfig {
    ai(ai)
    device(device)
    when (val method = initializeMethodsStateFlow.value) {
      InitializeMethods.Back -> {
        // default
      }

      InitializeMethods.Noop -> {
        addInterceptor(object : ArbiterInitializerInterceptor {
          override fun intercept(device: Device, chain: ArbiterInitializerInterceptor.Chain) {
            // do nothing
          }
        })
      }

      is InitializeMethods.OpenApp -> {
        addInterceptor(object : ArbiterInitializerInterceptor {
          override fun intercept(device: Device, chain: ArbiterInitializerInterceptor.Chain) {
            device.executeCommands(
              listOf(
                MaestroCommand(
                  launchAppCommand = LaunchAppCommand(
                    appId = method.packageName
                  )
                )
              )
            )
          }
        })
      }
    }
  }
}
