package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.ConfidentialInfo.removeConfidentialInfo
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

@ArbigentInternalApi
public var printLogger: (String) -> Unit = { println(it) }

private fun printLog(level: ArbigentLogLevel, rawLog: String, instance: Any? = null) {
  val log = rawLog.removeConfidentialInfo()
  val logContent =
    if (instance != null) {
      "${level.shortName()}: $log (${instance::class.simpleName})"
    } else {
      "${level.shortName()}: $log"
    }
  printLogger("Arbigent: $logContent")
  ArbigentFiles.logFile?.parentFile?.mkdirs()
  val date = arbigentLogFormatter.format(Instant.now().atZone(ZoneId.systemDefault()))
  ArbigentFiles.logFile?.appendText("$date $logContent\n")

  ArbigentGlobalStatus.log(logContent)
}

public object ConfidentialInfo {
  @ArbigentInternalApi
  private val _shouldBeRemovedStrings: MutableSet<String> = mutableSetOf()
  public val shouldBeRemovedStrings: Set<String> get() = _shouldBeRemovedStrings

  public fun addStringToBeRemoved(string: String) {
    if (string.isBlank()) {
      return
    }
    _shouldBeRemovedStrings.add(string)
  }

  @ArbigentInternalApi
  public fun String.removeConfidentialInfo(): String {
    return shouldBeRemovedStrings.fold(this) { acc, s ->
      acc.replace(s, "****")
    }
  }
}