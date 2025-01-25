package io.github.takahirom.arbigent

@ArbigentInternalApi
public var errorHandler: (Throwable) -> Unit = { e ->
  arbigentErrorLog(buildString {
    appendLine("An unexpected error occurred.")
    appendLine()
    appendLine(e.message)
    appendLine()
    appendLine(e.stackTraceToString())
  })
}