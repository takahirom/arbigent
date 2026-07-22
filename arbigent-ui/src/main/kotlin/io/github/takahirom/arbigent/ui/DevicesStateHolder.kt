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
import kotlinx.coroutines.runInterruptible
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
  // Serializes the cancel-then-replace of fetchJob: the OS-change collector and the UI refresh
  // handler can both call fetchDevices() from different threads, and an unguarded swap could leave
  // an older discovery running (and later clobbering a newer result).
  private val fetchLock = Any()

  init {
    selectedDeviceOs.onEach { fetchDevices() }.launchIn(scope)
  }

  fun onSelectedDeviceChanged(device: ArbigentAvailableDevice?) {
    arbigentDebugLog("onSelectedDeviceChanged: $device")
    _selectedDevice.value = device
  }

  fun fetchDevices() {
    synchronized(fetchLock) {
      // Accepted tradeoff: cancel() only requests cancellation, so a just-cancelled predecessor may
      // still be unwinding its (read-only) discovery when the replacement starts, briefly running
      // two discoveries. That is harmless — discovery mutates no device state, and the ensureActive()
      // guard below means only the latest job ever publishes its result.
      fetchJob?.cancel()
      fetchJob = scope.launch {
        val os = selectedDeviceOs.value
        // Device discovery (e.g. devicectl) blocks, so run it off the caller under runInterruptible
        // so cancelling the job actually interrupts the blocking call, and drop the result if a
        // newer fetch cancelled us while it ran.
        val result = withContext(ArbigentCoroutinesDispatcher.dispatcher) {
          runInterruptible { arbigentAvailableDeviceListFactory(os) }
        }
        ensureActive()
        devices.value = result
        reconcileSelection(result)
      }
    }
  }

  // Rediscovery produces fresh device objects that are never reference-equal to the previously
  // selected one (these device classes deliberately don't override equals), so re-point the
  // selection at the matching entry in the new list by its stable identity, or clear it if the
  // device is gone. Without this the UI keeps a stale object selected that no list entry matches.
  // stableKey carries the full device identifier (never displayed), so two real iPhones sharing a
  // masked UDID prefix are told apart and the selection can never re-point at the wrong device.
  private fun reconcileSelection(newDevices: List<ArbigentAvailableDevice>) {
    val previous = _selectedDevice.value ?: return
    _selectedDevice.value = newDevices.firstOrNull { it.stableKey == previous.stableKey }
  }
}
