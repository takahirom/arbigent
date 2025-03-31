package io.github.takahirom.arbigent

internal actual fun platformCurrentTimeMillis(): Long = kotlin.js.Date.now().toLong()