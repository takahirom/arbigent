package com.github.takahirom.arbiter

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object ArbiterCorotuinesDispatcher {
  var dispatcher: CoroutineDispatcher = Dispatchers.Default
}