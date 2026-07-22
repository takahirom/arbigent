package io.github.takahirom.arbigent.ui


import io.github.takahirom.arbigent.ArbigentAvailableDevice
import io.github.takahirom.arbigent.ArbigentDeviceOs
import io.github.takahirom.arbigent.arbigentDebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext


class DevicesStateHolder(
  val arbigentAvailableDeviceListFactory: (ArbigentDeviceOs) -> List<ArbigentAvailableDevice>,
  // Injected so tests run discovery on a TestDispatcher instead of a process-wide global.
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
  val selectedDeviceOs: MutableStateFlow<ArbigentDeviceOs> = MutableStateFlow(ArbigentDeviceOs.Android)
  val devices: MutableStateFlow<List<ArbigentAvailableDevice>> = MutableStateFlow(listOf())
  private val _selectedDevice: MutableStateFlow<ArbigentAvailableDevice?> = MutableStateFlow(null)
  val selectedDevice: StateFlow<ArbigentAvailableDevice?> = _selectedDevice.asStateFlow()

  // One scope owned by the holder. A single collector re-fetches when the OS changes; each fetch is a
  // cancellable one-shot whose predecessor is cancelled first, so refreshes never accumulate
  // collectors or let a slow discovery overwrite a newer result out of order.
  private val scope = CoroutineScope(dispatcher + SupervisorJob())
  private var fetchJob: Job? = null
  // Monotonic id of the latest fetch. A job publishes only if it still matches this when it finishes,
  // which makes the "am I still the newest?" check and the state writes atomic under fetchLock.
  private var fetchGeneration = 0
  // Serializes the cancel-then-replace of fetchJob (and the generation bump/publish): the OS-change
  // collector and the UI refresh handler can both call fetchDevices() from different threads, and an
  // unguarded swap could leave an older discovery running and later clobbering a newer result.
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
      // cancel() only requests cancellation, so a just-cancelled predecessor may still be unwinding
      // its (read-only, state-free) discovery when the replacement starts, briefly running two
      // discoveries — harmless. What is NOT harmless is a stale job publishing after a newer one: a
      // job that already passed a cancellation check can still be preempted before its writes, so a
      // bare ensureActive() leaves a check-then-act window. Instead each job captures the generation
      // it was launched under and, holding fetchLock, publishes only if it is still the latest — so
      // the newest fetch always wins regardless of completion order.
      fetchJob?.cancel()
      val generation = ++fetchGeneration
      fetchJob = scope.launch {
        val os = selectedDeviceOs.value
        // Device discovery (e.g. devicectl) blocks, so run it off the caller under runInterruptible
        // so cancelling the job actually interrupts the blocking call.
        val result = withContext(dispatcher) {
          runInterruptible { arbigentAvailableDeviceListFactory(os) }
        }
        synchronized(fetchLock) {
          if (generation != fetchGeneration) return@launch
          devices.value = result
          reconcileSelection(result)
        }
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

  // Cancel the holder-owned scope so the OS collector and any in-flight discovery don't outlive the
  // owning app state holder. Called from ArbigentAppStateHolder.close().
  fun close() {
    scope.cancel()
  }
}
