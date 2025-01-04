package com.github.takahirom.arbiter

public enum class ArbiterLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

public var arbiterLogLevel: ArbiterLogLevel = ArbiterLogLevel.DEBUG

public fun arbiterDebugLog(log: String) {
    if (arbiterLogLevel <= ArbiterLogLevel.DEBUG) {
        println("ArbiterLog: $log")
    }
}

public fun arbiterDebugLog(log: () -> String) {
    if (arbiterLogLevel <= ArbiterLogLevel.DEBUG) {
        println("ArbiterLog: ${log()}")
    }
}

public fun arbiterInfoLog(log: String) {
    if (arbiterLogLevel <= ArbiterLogLevel.INFO) {
        println("ArbiterLog: $log")
    }
}

public fun arbiterInfoLog(log: () -> String) {
    if (arbiterLogLevel <= ArbiterLogLevel.INFO) {
        println("ArbiterLog: ${log()}")
    }
}

public fun arbiterErrorLog(log: String) {
    if (arbiterLogLevel <= ArbiterLogLevel.ERROR) {
        println("ArbiterLog: $log")
    }
}