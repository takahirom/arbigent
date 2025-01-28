package io.github.takahirom.arbigent

import kotlinx.coroutines.flow.*
import java.time.Instant

// Just for showing the status of the global status
public object ArbigentGlobalStatus {
  private val statusFlow: MutableStateFlow<String> = MutableStateFlow("Device Not connected")
  public val status: Flow<String> = statusFlow.asStateFlow()
  public fun status(): String = statusFlow.value
  private val consoleFlow: MutableStateFlow<List<Pair<Instant,String>>> = MutableStateFlow(listOf())
  public val console: Flow<List<Pair<Instant,String>>> = consoleFlow.asStateFlow()
  public fun console(): List<Pair<Instant,String>> = consoleFlow.value
  private fun set(value: String) {
    arbigentInfoLog("Status: $value")
    statusFlow.value = value
  }

  public fun log(value: String) {
    consoleFlow.value += (Instant.now() to value)
    if (consoleFlow.value.size > 1000) {
      consoleFlow.value = consoleFlow.value.drop(1)
    }
  }

  public fun<T : Any> onConnect(block: () -> T): T {
    return on("Device Connecting", block, "Device Connected")
  }

  public fun<T : Any> onDisconnect(block: () -> T): T {
    return on("Device Disconnecting", block, "Device Not connected")
  }

  public fun<T : Any> onAi(block: () -> T): T {
    return on("AI processing...", block)
  }

  public fun<T : Any> onInitializing(block: () -> T): T {
    return on("Initializing..", block)
  }

  public fun<T : Any> onDevice(command:String, block: () -> T): T {
    return on("Processing device command: $command", block)
  }

  public fun<T : Any> onImageAssertion(assertionPrompt: String, block: () -> T): T {
    return on("Running image assertion...:$assertionPrompt", block)
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

  public fun<T: Any> onAiRateLimitWait(waitSec: Long, block: () -> T): T {
    return on("AI rate limit exceeded. Waiting $waitSec s", block, "AI processing...")
  }

  public fun onFinished() {
    set("Operation completed successfully")
  }

  public fun onCanceled() {
    set("Operation canceled by user")
  }

  public fun onError(e: Throwable) {
    set("Error ${e.message}")
  }
}