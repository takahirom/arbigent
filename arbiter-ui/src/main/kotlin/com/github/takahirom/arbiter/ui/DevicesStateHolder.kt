package com.github.takahirom.arbiter.ui


import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.AvailableDevice
import com.github.takahirom.arbiter.DeviceType
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import util.LocalSimulatorUtils


class DevicesStateHolder {
  val deviceType: MutableStateFlow<DeviceType> = MutableStateFlow(DeviceType.Android)
  val devices: MutableStateFlow<List<AvailableDevice>> = MutableStateFlow(listOf())
  val selectedDevice: MutableStateFlow<AvailableDevice?> = MutableStateFlow(null)

  init {
    fetchDevices()
  }

  fun fetchDevices() {
    deviceType.onEach { deviceType ->
      devices.value = if (deviceType == DeviceType.Android) {
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
    }.launchIn(CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob()))
  }
}