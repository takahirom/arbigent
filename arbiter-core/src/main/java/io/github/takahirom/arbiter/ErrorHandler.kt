package io.github.takahirom.arbiter

@ArbiterInternalApi
public var errorHandler: (Throwable) -> Unit = { e ->
  println("An unexpected error occurred.")
  println(e.message)
  println(e.stackTraceToString())
}