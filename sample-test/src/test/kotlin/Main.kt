package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.arbiter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class Test {
  @Test
  fun tests() = runTest{
    val arbiter = arbiter {
      maestroInstance(com.github.takahirom.arbiter.maestroInstance)
    }
    arbiter.execute("Open YouTube")
    arbiter.waitUntilFinished()
  }
}
