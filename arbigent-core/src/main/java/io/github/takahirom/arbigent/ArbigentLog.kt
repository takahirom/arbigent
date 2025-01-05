package io.github.takahirom.arbigent

public enum class ArbigentLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

public var arbigentLogLevel: ArbigentLogLevel = ArbigentLogLevel.DEBUG

public fun arbigentDebugLog(log: String) {
    if (arbigentLogLevel <= ArbigentLogLevel.DEBUG) {
        println("ArbigentLog: $log")
    }
}

public fun arbigentDebugLog(log: () -> String) {
    if (arbigentLogLevel <= ArbigentLogLevel.DEBUG) {
        println("ArbigentLog: ${log()}")
    }
}

public fun arbigentInfoLog(log: String) {
    if (arbigentLogLevel <= ArbigentLogLevel.INFO) {
        println("ArbigentLog: $log")
    }
}

public fun arbigentInfoLog(log: () -> String) {
    if (arbigentLogLevel <= ArbigentLogLevel.INFO) {
        println("ArbigentLog: ${log()}")
    }
}

public fun arbigentErrorLog(log: String) {
    if (arbigentLogLevel <= ArbigentLogLevel.ERROR) {
        println("ArbigentLog: $log")
    }
}