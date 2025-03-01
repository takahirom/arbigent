package io.github.takahirom.arbigent

internal actual class DefaultTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = kotlin.js.Date.now().toLong()
}