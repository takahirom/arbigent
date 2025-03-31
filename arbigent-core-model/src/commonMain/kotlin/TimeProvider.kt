package io.github.takahirom.arbigent

public interface TimeProvider {
    public fun currentTimeMillis(): Long

    public companion object {
        private var instance: TimeProvider = DefaultTimeProvider()

        public fun get(): TimeProvider = instance

        public fun set(provider: TimeProvider) {
            instance = provider
        }
    }
}

// Platform-specific function to get the current time in milliseconds
internal expect fun platformCurrentTimeMillis(): Long

// Implementation that delegates to the platform-specific function
internal class DefaultTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = platformCurrentTimeMillis()
}

public class TestTimeProvider(private var currentTime: Long = 0L) : TimeProvider {
    override fun currentTimeMillis(): Long = currentTime

    public fun setCurrentTime(time: Long) {
        currentTime = time
    }

    public fun advanceBy(milliseconds: Long) {
        currentTime += milliseconds
    }
}
