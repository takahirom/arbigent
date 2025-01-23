package io.github.takahirom.arbigent

import kotlin.test.Test
import kotlin.test.assertEquals

class ArbigentShardTest {
  @Test
  fun shard() {
    class TestCase(val input: List<Int>, val shard: ArbigentShard, val expected: List<Int>)
    listOf(
      TestCase(listOf(1, 2), ArbigentShard(1, 2), listOf(1)),
      TestCase(listOf(1, 2), ArbigentShard(2, 2), listOf(2)),
      TestCase(listOf(1), ArbigentShard(1, 2), listOf(1)),
      TestCase(listOf(1), ArbigentShard(2, 2), listOf()),
      TestCase(listOf(1, 2), ArbigentShard(4, 4), listOf()),
      TestCase(listOf(1, 2, 3), ArbigentShard(1, 2), listOf(1, 2)),
      TestCase(listOf(1, 2, 3), ArbigentShard(2, 2), listOf(3)),
      TestCase(listOf(1, 2, 3, 4), ArbigentShard(1, 3), listOf(1, 2)),
      TestCase(listOf(1, 2, 3, 4), ArbigentShard(2, 3), listOf(3)),
      TestCase(listOf(1, 2, 3, 4), ArbigentShard(3, 3), listOf(4)),
      TestCase(listOf(1, 2, 3, 4), ArbigentShard(1, 2), listOf(1, 2)),
      TestCase(listOf(1, 2, 3, 4), ArbigentShard(2, 2), listOf(3, 4)),
    ).forEach {
      assertEquals(it.expected, it.input.shard(it.shard), "input: ${it.input}, shard: ${it.shard}")
    }
  }
}