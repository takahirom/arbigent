package io.github.takahirom.arbiter

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

public object ArbiterCoroutinesDispatcher {
  public var dispatcher: CoroutineDispatcher = Dispatchers.Default
}