package com.github.takahirom.arbiter.ui

import com.github.takahirom.arbiter.ArbiterInitializerInterceptor
import com.github.takahirom.arbiter.Device
import com.github.takahirom.arbiter.OpenAIAi
import com.github.takahirom.arbiter.arbiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand

sealed interface InitializeMethods {
  object Back : InitializeMethods
  data class OpenApp(val packageName: String) : InitializeMethods
}

class ScenarioStateHolder(private val device: Device) {
  // (var goal: String?, var arbiter: Arbiter?)
  val goalStateFlow: MutableStateFlow<String> = MutableStateFlow("")
  val goal get() = goalStateFlow.value
  val initializeMethodsStateFlow: MutableStateFlow<InitializeMethods> =
    MutableStateFlow(InitializeMethods.Back)
  val arbiterStateFlow = combine(initializeMethodsStateFlow) {
    createArbiter(device)
  }.stateIn(
    scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    started = SharingStarted.WhileSubscribed(),
    initialValue = null
  )
  private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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
  val contextStateFlow = arbiterStateFlow
    .flatMapLatest { it?.arbiterContextHolderStateFlow ?: flowOf() }
    .stateIn(
      scope = coroutineScope,
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

  private fun createArbiter(device: Device) = arbiter {
    ai(OpenAIAi(System.getenv("API_KEY")!!))
    device(device)
    when (val method = initializeMethodsStateFlow.value) {
      InitializeMethods.Back -> {
        // default
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
