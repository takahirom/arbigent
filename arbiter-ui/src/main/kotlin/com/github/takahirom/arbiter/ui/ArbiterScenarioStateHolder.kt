package com.github.takahirom.arbiter.ui

import androidx.compose.foundation.text.input.TextFieldState
import com.github.takahirom.arbiter.ArbiterAi
import com.github.takahirom.arbiter.ArbiterScenarioExecutor
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.ArbiterInitializerInterceptor
import com.github.takahirom.arbiter.ArbiterDevice
import com.github.takahirom.arbiter.ArbiterScenarioDeviceFormFactor
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
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand

@Serializable
sealed interface CleanupData {
  @Serializable
  @SerialName("Noop")
  data object Noop : CleanupData

  @Serializable
  @SerialName("Cleanup")
  data class Cleanup(val packageName: String) : CleanupData
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


class ArbiterScenarioStateHolder(initialDevice: ArbiterDevice, private val ai: ArbiterAi) {
  private val deviceStateFlow = MutableStateFlow(initialDevice)
  val device get() = deviceStateFlow.value
  val goalState = TextFieldState("")
  val goal get() = goalState.text.toString()
  val maxRetryState: TextFieldState = TextFieldState("3")
  val maxTurnState: TextFieldState = TextFieldState("10")
  val cleanupDataStateFlow: MutableStateFlow<CleanupData> = MutableStateFlow(CleanupData.Noop)
  val initializeMethodsStateFlow: MutableStateFlow<InitializeMethods> =
    MutableStateFlow(InitializeMethods.Back)
  val deviceFormFactorStateFlow: MutableStateFlow<ArbiterScenarioDeviceFormFactor> =
    MutableStateFlow(ArbiterScenarioDeviceFormFactor.Mobile)
  val dependencyScenarioStateFlow = MutableStateFlow<ArbiterScenarioStateHolder?>(null)
  val arbiterStateFlow = MutableStateFlow<ArbiterScenarioExecutor?>(null)
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

  val runningInfo: StateFlow<ArbiterScenarioExecutor.RunningInfo?> = arbiterStateFlow
    .flatMapLatest { it?.runningInfoStateFlow ?: flowOf() }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = null
    )

  suspend fun onExecute(arbiterExecutorScenario: ArbiterScenarioExecutor.ArbiterExecutorScenario) {
    arbiterStateFlow.value?.cancel()
    val arbiter = ArbiterScenarioExecutor {
    }
    arbiterStateFlow.value = arbiter
    arbiterStateFlow.value!!.execute(
      arbiterExecutorScenario,
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

  fun onDeviceChanged(device: ArbiterDevice) {
    deviceStateFlow.value = device
  }

  fun createAgentConfig() = AgentConfig {
    ai(ai)
    device(device)
    deviceFormFactor(deviceFormFactorStateFlow.value)
    when (val method = initializeMethodsStateFlow.value) {
      InitializeMethods.Back -> {
        // default
      }

      InitializeMethods.Noop -> {
        addInterceptor(object : ArbiterInitializerInterceptor {
          override fun intercept(device: ArbiterDevice, chain: ArbiterInitializerInterceptor.Chain) {
            // do nothing
          }
        })
      }

      is InitializeMethods.OpenApp -> {
        addInterceptor(object : ArbiterInitializerInterceptor {
          override fun intercept(device: ArbiterDevice, chain: ArbiterInitializerInterceptor.Chain) {
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
    when (val cleanupData = cleanupDataStateFlow.value) {
      CleanupData.Noop -> {
        // default
      }

      is CleanupData.Cleanup -> {
        addInterceptor(object : ArbiterInitializerInterceptor {
          override fun intercept(device: ArbiterDevice, chain: ArbiterInitializerInterceptor.Chain) {
            device.executeCommands(
              listOf(
                MaestroCommand(
                  clearStateCommand = ClearStateCommand(
                    appId = cleanupData.packageName
                  )
                )
              )
            )
            chain.proceed(device)
          }
        })
      }
    }
  }
}
