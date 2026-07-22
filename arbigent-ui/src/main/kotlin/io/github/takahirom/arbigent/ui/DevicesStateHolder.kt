package io.github.takahirom.arbigent.ui


import io.github.takahirom.arbigent.ArbigentCoroutinesDispatcher
import io.github.takahirom.arbigent.ArbigentAvailableDevice
import io.github.takahirom.arbigent.ArbigentDeviceOs
import io.github.takahirom.arbigent.arbigentDebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class DevicesStateHolder(val arbigentAvailableDeviceListFactory: (ArbigentDeviceOs) -> List<ArbigentAvailableDevice>) {
  val selectedDeviceOs: MutableStateFlow<ArbigentDeviceOs> = MutableStateFlow(ArbigentDeviceOs.Android)
  val devices: MutableStateFlow<List<ArbigentAvailableDevice>> = MutableStateFlow(listOf())
  private val _selectedDevice: MutableStateFlow<ArbigentAvailableDevice?> = MutableStateFlow(null)
  val selectedDevice: StateFlow<ArbigentAvailableDevice?> = _selectedDevice.asStateFlow()

  // One scope owned by the holder. A single collector re-fetches when the OS changes; each fetch is a
  // cancellable one-shot whose predecessor is cancelled first, so refreshes never accumulate
  // collectors or let a slow discovery overwrite a newer result out of order.
  private val scope = CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob())
  private var fetchJob: Job? = null

  init {
    selectedDeviceOs.onEach { fetchDevices() }.launchIn(scope)
  }

  fun onSelectedDeviceChanged(device: ArbigentAvailableDevice?) {
    arbigentDebugLog("onSelectedDeviceChanged: $device")
    _selectedDevice.value = device
  }

  fun fetchDevices() {
    fetchJob?.cancel()
    fetchJob = scope.launch {
      val os = selectedDeviceOs.value
      // Device discovery (e.g. devicectl) blocks, so run it off the caller and drop the result if a
      // newer fetch cancelled us while it ran.
      val result = withContext(ArbigentCoroutinesDispatcher.dispatcher) {
        arbigentAvailableDeviceListFactory(os)
      }
      ensureActive()
      devices.value = result
    }
  }
}
