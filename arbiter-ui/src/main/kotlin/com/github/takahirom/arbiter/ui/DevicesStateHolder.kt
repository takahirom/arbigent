package com.github.takahirom.arbiter.ui


import com.github.takahirom.arbiter.ArbiterCoroutinesDispatcher
import com.github.takahirom.arbiter.AvailableDevice
import com.github.takahirom.arbiter.DeviceOs
import com.github.takahirom.arbiter.arbiterDebugLog
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import util.LocalSimulatorUtils


class DevicesStateHolder {
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
    selectedDeviceOs.onEach { deviceType ->
      devices.value = if (deviceType == DeviceOs.Android) {
        Dadb.list().map { AvailableDevice.Android(it) }
      } else {
        LocalSimulatorUtils.list()
          .devices
          .flatMap { runtime ->
            runtime.value
              .filter { it.isAvailable && it.state == "Booted" }
          }
          .map { AvailableDevice.IOS(it) }
      }
    }.launchIn(CoroutineScope(ArbiterCoroutinesDispatcher.dispatcher + SupervisorJob()))
  }
}