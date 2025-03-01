package io.github.takahirom.arbigent

internal actual class DefaultTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}