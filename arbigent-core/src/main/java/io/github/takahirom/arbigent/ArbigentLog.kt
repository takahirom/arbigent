package io.github.takahirom.arbigent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

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

@ArbigentInternalApi
public val arbigentLogFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
  .appendValue(ChronoField.MONTH_OF_YEAR, 2)
  .appendLiteral('/')
  .appendValue(ChronoField.DAY_OF_MONTH, 2)
  .appendLiteral(' ')
  .appendValue(ChronoField.HOUR_OF_DAY, 2)
  .appendLiteral(':')
  .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
  .optionalStart()
  .appendLiteral(':')
  .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
  .optionalStart()
  .appendFraction(ChronoField.NANO_OF_SECOND, 3, 3, true)
  .toFormatter()

private fun printLog(level: ArbigentLogLevel, log: String, instance: Any? = null) {
  val logContent =
    if (instance != null) {
      "${level.shortName()}: $log ($instance)"
    } else {
      "${level.shortName()}: $log"
    }
  println("Arbigent: $logContent")
  ArbigentFiles.logFile?.parentFile?.mkdirs()
  val date = arbigentLogFormatter.format(Instant.now().atZone(ZoneId.systemDefault()))
  ArbigentFiles.logFile?.appendText("$date $logContent\n")

  ArbigentGlobalStatus.log(logContent)
}