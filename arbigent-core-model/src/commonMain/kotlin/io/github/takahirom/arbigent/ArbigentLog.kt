package io.github.takahirom.arbigent

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

// Simplified logging that relies on kermit's global configuration
// This will be controlled by arbigent-core's log level settings

private val logger = Logger.withTag("Arbigent")

public fun arbigentDebugLog(log: String) {
  logger.d(log)
}

public fun arbigentDebugLog(log: () -> String) {
  logger.d(log())
}

public fun arbigentInfoLog(log: String) {
  logger.i(log)
}

public fun arbigentInfoLog(log: () -> String) {
  logger.i(log())
}

public fun arbigentWarnLog(log: String) {
  logger.w(log)
}

public fun arbigentWarnLog(log: () -> String) {
  logger.w(log())
}

public fun arbigentErrorLog(log: String) {
  logger.e(log)
}

public fun arbigentErrorLog(log: () -> String) {
  logger.e(log())
}