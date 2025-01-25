package io.github.takahirom.arbigent

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

public var arbigentLogLevel: ArbigentLogLevel = ArbigentLogLevel.DEBUG

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

public fun arbigentErrorLog(log: String) {
  if (arbigentLogLevel <= ArbigentLogLevel.ERROR) {
    printLog(ArbigentLogLevel.ERROR, log)
  }
}

private fun printLog(level: ArbigentLogLevel, log: String, instance: Any? = null) {
  val logContent =
    if (instance != null) {
      "${level.shortName()}: $log ($instance)"
    } else {
      "${level.shortName()}: $log"
    }
  println("Arbigent: $logContent")
  ArbigentFiles.logFile?.parentFile?.mkdirs()
  ArbigentFiles.logFile?.appendText("$logContent\n")

  ArbigentGlobalStatus.log(logContent)
}