package io.github.takahirom.arbigent.ui


import io.github.takahirom.arbigent.ArbigentCoroutinesDispatcher
import io.github.takahirom.arbigent.ArbigentAvailableDevice
import io.github.takahirom.arbigent.ArbigentDeviceOs
import io.github.takahirom.arbigent.arbigentDebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach


class DevicesStateHolder(val arbigentAvailableDeviceListFactory: (ArbigentDeviceOs) -> List<ArbigentAvailableDevice>) {
  val selectedDeviceOs: MutableStateFlow<ArbigentDeviceOs> = MutableStateFlow(ArbigentDeviceOs.Android)
  val devices: MutableStateFlow<List<ArbigentAvailableDevice>> = MutableStateFlow(listOf())
  private val _selectedDevice: MutableStateFlow<ArbigentAvailableDevice?> = MutableStateFlow(null)
  val selectedDevice: StateFlow<ArbigentAvailableDevice?> = _selectedDevice.asStateFlow()

  init {
    fetchDevices()
  }

  fun onSelectedDeviceChanged(device: ArbigentAvailableDevice?) {
    arbigentDebugLog("onSelectedDeviceChanged: $device")
    _selectedDevice.value = device
  }

  fun fetchDevices() {
    selectedDeviceOs.onEach { os ->
      devices.value = arbigentAvailableDeviceListFactory(os)
    }.launchIn(CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob()))
  }
}
