package io.github.takahirom.arbigent

import kotlinx.coroutines.flow.*

// Just for showing the status of the global status
public object ArbigentGlobalStatus {
  private val statusFlow: MutableStateFlow<String> = MutableStateFlow("Device Not connected")
  public val status: Flow<String> = statusFlow.asStateFlow()
  public fun status(): String = statusFlow.value
  private fun set(value: String) {
    arbigentDebugLog("status: $value")
    statusFlow.value = value
  }

  public fun<T : Any> onConnect(block: () -> T): T {
    return on("Device Connecting", block, "Device Connected")
  }

  public fun<T : Any> onDisconnect(block: () -> T): T {
    return on("Device Disconnecting", block, "Device Not connected")
  }

  public fun<T : Any> onAi(block: () -> T): T {
    return on("Ai..", block)
  }

  public fun<T : Any> onInitializing(block: () -> T): T {
    return on("Initializing..", block)
  }

  public fun<T : Any> onDevice(command:String, block: () -> T): T {
    return on("Device..  command:$command", block)
  }

  public fun<T : Any> onImageAssertion(block: () -> T): T {
    return on("Image assertion..", block)
  }

  private fun<T : Any> on(text:String, block: () -> T, afterText: String = "Arbigent..."): T {
    set(text)
    try {
      return block()
    } catch (e: Throwable) {
      set("Error ${e.message} during $text")
      throw e
    } finally {
      set(afterText)
    }
  }

  public fun<T: Any> onRateLimitWait(waitSec: Long, block: () -> T): T {
    return on("Rate limit wait. $waitSec s", block, "Ai..")
  }

  public fun onFinished() {
    set("Finished")
  }

  public fun onCanceled() {
    set("Canceled")
  }

  public fun onError(e: Throwable) {
    set("Error ${e.message}")
  }
}