package com.github.takahirom.arbiter

enum class ArbiterLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

var arbiterLogLevel = ArbiterLogLevel.DEBUG

fun arbiterDebugLog(log: String) {
    if (arbiterLogLevel <= ArbiterLogLevel.DEBUG) {
        println("ArbiterLog: $log")
    }
}

fun arbiterDebugLog(log: () -> String) {
    if (arbiterLogLevel <= ArbiterLogLevel.DEBUG) {
        println("ArbiterLog: ${log()}")
    }
}

fun arbiterInfoLog(log: String) {
    if (arbiterLogLevel <= ArbiterLogLevel.INFO) {
        println("ArbiterLog: $log")
    }
}

fun arbiterInfoLog(log: () -> String) {
    if (arbiterLogLevel <= ArbiterLogLevel.INFO) {
        println("ArbiterLog: ${log()}")
    }
}