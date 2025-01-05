package io.github.takahirom.arbigent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

public object ArbigentCoroutinesDispatcher {
  public var dispatcher: CoroutineDispatcher = Dispatchers.Default
}