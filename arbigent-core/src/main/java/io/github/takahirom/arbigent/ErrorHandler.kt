package io.github.takahirom.arbigent

@ArbigentInternalApi
public var errorHandler: (Throwable) -> Unit = { e ->
  println("An unexpected error occurred.")
  println(e.message)
  println(e.stackTraceToString())
}