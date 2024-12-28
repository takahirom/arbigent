package com.github.takahirom.arbiter.ui

import com.github.takahirom.arbiter.Ai
import com.github.takahirom.arbiter.Arbiter
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.ArbiterInitializerInterceptor
import com.github.takahirom.arbiter.Device
import com.github.takahirom.arbiter.RunningInfo
import com.github.takahirom.arbiter.agentConfig
import com.github.takahirom.arbiter.arbiter
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
sealed interface InitializeMethods {
  @Serializable
  @SerialName("Back")
  object Back : InitializeMethods

  @Serializable
  @SerialName("Noop")
  object Noop : InitializeMethods

  @Serializable
  @SerialName("OpenApp")
  data class OpenApp(val packageName: String) : InitializeMethods
}

class ScenarioStateHolder(val device: Device, val ai: Ai) {
  // (var goal: String?, var arbiter: Arbiter?)
  val goalStateFlow: MutableStateFlow<String> = MutableStateFlow("")
  val goal get() = goalStateFlow.value
  val maxRetryStateFlow: MutableStateFlow<Int> = MutableStateFlow(3)
  val maxTurnStateFlow: MutableStateFlow<Int> = MutableStateFlow(10)
  val initializeMethodsStateFlow: MutableStateFlow<InitializeMethods> =
    MutableStateFlow(InitializeMethods.Back)
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
    val arbiter = arbiter {
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
    goalStateFlow.value = goal
  }

  fun createAgentConfig(device: Device, ai: Ai) = agentConfig {
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
