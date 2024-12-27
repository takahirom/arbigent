package com.github.takahirom.arbiter.ui

import com.github.takahirom.arbiter.Ai
import com.github.takahirom.arbiter.Arbiter
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.ArbiterInitializerInterceptor
import com.github.takahirom.arbiter.Device
import com.github.takahirom.arbiter.agentConfig
import com.github.takahirom.arbiter.arbiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand

sealed interface InitializeMethods {
  object Back : InitializeMethods
  object Noop : InitializeMethods
  data class OpenApp(val packageName: String) : InitializeMethods
}

class ScenarioStateHolder(val device: Device, val ai: Ai) {
  // (var goal: String?, var arbiter: Arbiter?)
  val goalStateFlow: MutableStateFlow<String> = MutableStateFlow("")
  val goal get() = goalStateFlow.value
  val initializeMethodsStateFlow: MutableStateFlow<InitializeMethods> =
    MutableStateFlow(InitializeMethods.Back)
  val dependencyScenarioStateFlow = MutableStateFlow<String?>(null)
  val arbiterStateFlow = MutableStateFlow<Arbiter?>(null)
  private val coroutineScope =
    CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob())
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

  suspend fun execute(scenario: Arbiter.Scenario) {
    arbiterStateFlow.value?.cancel()
    val arbiter = arbiter{
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
