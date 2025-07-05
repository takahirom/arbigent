package io.github.takahirom.arbigent

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.takahirom.arbigent.ConfidentialInfo.removeConfidentialInfo
import kotlin.time.Clock

@RequiresOptIn(
  message = "This API is internal to Arbigent and should not be used from outside.",
  level = RequiresOptIn.Level.ERROR
)
public annotation class ArbigentInternalApi

public enum class ArbigentLogLevel {
  DEBUG,
  INFO,
  WARN,
  ERROR;

  public fun shortName(): String {
    return when (this) {
      DEBUG -> "D"
      INFO -> "I"
      WARN -> "W"
      ERROR -> "E"
    }
  }
}

private var _arbigentLogLevel: ArbigentLogLevel = run {
  updateKermit(ArbigentLogLevel.INFO)
  ArbigentLogLevel.INFO
}

public var arbigentLogLevel: ArbigentLogLevel
  get() = _arbigentLogLevel
  set(value) {
    _arbigentLogLevel = value
    updateKermit(value)
  }

private fun updateKermit(value: ArbigentLogLevel) {
  Logger.setLogWriters(object : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
      when (severity) {
        Severity.Debug -> printLog(ArbigentLogLevel.DEBUG, message)
        Severity.Info -> printLog(ArbigentLogLevel.INFO, message)
        Severity.Warn -> printLog(ArbigentLogLevel.WARN, message)
        Severity.Error -> printLog(ArbigentLogLevel.ERROR, message)
        else -> printLog(ArbigentLogLevel.INFO, message)
      }
    }
  })
}

public fun arbigentDebugLog(log: String) {
  if (arbigentLogLevel <= ArbigentLogLevel.DEBUG) {
    printLog(ArbigentLogLevel.DEBUG, log)
  }
}

public fun Any?.arbigentDebugLog(log: String) {
  if (arbigentLogLevel <= ArbigentLogLevel.DEBUG) {
    if (this == null) {
      printLog(ArbigentLogLevel.DEBUG, log)
    } else {
      printLog(ArbigentLogLevel.DEBUG, log, this)
    }
  }
}

public fun Any?.arbigentDebugLog(log: () -> String) {
  if (arbigentLogLevel <= ArbigentLogLevel.DEBUG) {
    if (this == null) {
      printLog(ArbigentLogLevel.DEBUG, log(), this)
    } else {
      printLog(ArbigentLogLevel.DEBUG, log(), this)
    }
  }
}

public fun arbigentInfoLog(log: String) {
  if (arbigentLogLevel <= ArbigentLogLevel.INFO) {
    printLog(ArbigentLogLevel.INFO, log)
  }
}

public fun arbigentInfoLog(log: () -> String) {
  if (arbigentLogLevel <= ArbigentLogLevel.INFO) {
    printLog(ArbigentLogLevel.INFO, log())
  }
}

public fun arbigentWarnLog(log: String) {
  if (arbigentLogLevel <= ArbigentLogLevel.WARN) {
    printLog(ArbigentLogLevel.WARN, log)
  }
}

public fun arbigentWarnLog(log: () -> String) {
  if (arbigentLogLevel <= ArbigentLogLevel.WARN) {
    printLog(ArbigentLogLevel.WARN, log())
  }
}

public fun arbigentErrorLog(log: String) {
  if (arbigentLogLevel <= ArbigentLogLevel.ERROR) {
    printLog(ArbigentLogLevel.ERROR, log)
  }
}

public fun arbigentErrorLog(log: () -> String) {
  if (arbigentLogLevel <= ArbigentLogLevel.ERROR) {
    printLog(ArbigentLogLevel.ERROR, log())
  }
}

@ArbigentInternalApi
@OptIn(kotlin.time.ExperimentalTime::class)
public var printLogger: (String) -> Unit = { log -> 
  // Add time formatting for console output (non-interactive mode)
  val timeText = Clock.System.now().toString().substring(11, 19)
  println("$timeText $log")
}

@OptIn(ArbigentInternalApi::class, kotlin.time.ExperimentalTime::class)
private fun printLog(level: ArbigentLogLevel, rawLog: String, instance: Any? = null) {
  val log = rawLog.removeConfidentialInfo()
  val logContent =
    if (instance != null && instance::class.simpleName != null) {
      "${level.shortName()}: $log (${instance::class.simpleName})"
    } else {
      "${level.shortName()}: $log"
    }

  // Route through printLogger (configured differently for interactive vs non-interactive mode)
  printLogger(logContent)
}

public object ConfidentialInfo {
  private val _shouldBeRemovedStrings: MutableMap<String, String> = mutableMapOf()
  public val shouldBeRemovedStrings: Map<String, String> get() = _shouldBeRemovedStrings

  public fun addStringToBeRemoved(string: String, replaceTo: String = "****") {
    if (string.isBlank()) {
      return
    }
    _shouldBeRemovedStrings[string] = replaceTo
  }

  public fun String.removeConfidentialInfo(): String {
    return shouldBeRemovedStrings.entries.fold(this) { acc, s ->
      acc.replace(s.key, s.value, ignoreCase = true)
    }
  }
}