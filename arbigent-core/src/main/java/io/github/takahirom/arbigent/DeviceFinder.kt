package io.github.takahirom.arbigent

import dadb.Dadb
import util.LocalSimulatorUtils

@ArbigentInternalApi
public fun fetchAvailableDevicesByOs(deviceType: DeviceOs): List<ArbigentAvailableDevice> {
  return when (deviceType) {
    DeviceOs.Android -> {
      Dadb.list().map { ArbigentAvailableDevice.Android(it) }
    }

    DeviceOs.iOS -> {
      LocalSimulatorUtils.list()
        .devices
        .flatMap { runtime ->
          runtime.value
            .filter { it.isAvailable && it.state == "Booted" }
        }
        .map { ArbigentAvailableDevice.IOS(it) }
    }

    else -> {
      listOf(ArbigentAvailableDevice.Web())
    }
  }
}