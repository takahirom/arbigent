package io.github.takahirom.arbiter.ui


import io.github.takahirom.arbiter.ArbiterCoroutinesDispatcher
import io.github.takahirom.arbiter.AvailableDevice
import io.github.takahirom.arbiter.DeviceOs
import io.github.takahirom.arbiter.arbiterDebugLog
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
    arbiterDebugLog("onSelectedDeviceChanged: $device")
    _selectedDevice.value = device
  }

  fun fetchDevices() {
    selectedDeviceOs.onEach { os ->
      devices.value = availableDeviceListFactory(os)
    }.launchIn(CoroutineScope(ArbiterCoroutinesDispatcher.dispatcher + SupervisorJob()))
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