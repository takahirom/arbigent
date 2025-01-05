package io.github.takahirom.arbigent.ui


import io.github.takahirom.arbigent.ArbigentCoroutinesDispatcher
import io.github.takahirom.arbigent.AvailableDevice
import io.github.takahirom.arbigent.DeviceOs
import io.github.takahirom.arbigent.arbigentDebugLog
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import util.LocalSimulatorUtils


class DevicesStateHolder(val availableDeviceListFactory: (DeviceOs) -> List<AvailableDevice>) {
  val selectedDeviceOs: MutableStateFlow<DeviceOs> = MutableStateFlow(DeviceOs.Android)
  val devices: MutableStateFlow<List<AvailableDevice>> = MutableStateFlow(listOf())
  private val _selectedDevice: MutableStateFlow<AvailableDevice?> = MutableStateFlow(null)
  val selectedDevice: StateFlow<AvailableDevice?> = _selectedDevice.asStateFlow()

  init {
    fetchDevices()
  }

  fun onSelectedDeviceChanged(device: AvailableDevice?) {
    arbigentDebugLog("onSelectedDeviceChanged: $device")
    _selectedDevice.value = device
  }

  fun fetchDevices() {
    selectedDeviceOs.onEach { os ->
      devices.value = availableDeviceListFactory(os)
    }.launchIn(CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob()))
  }
}

internal fun fetchAvailableDevicesByOs(deviceType: DeviceOs): List<AvailableDevice> {
  return when (deviceType) {
    DeviceOs.Android -> {
      Dadb.list().map { AvailableDevice.Android(it) }
    }

    DeviceOs.iOS -> {
      LocalSimulatorUtils.list()
        .devices
        .flatMap { runtime ->
          runtime.value
            .filter { it.isAvailable && it.state == "Booted" }
        }
        .map { AvailableDevice.IOS(it) }
    }

    else -> {
      listOf(AvailableDevice.Web())
    }
  }
}