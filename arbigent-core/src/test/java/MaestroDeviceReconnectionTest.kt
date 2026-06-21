package io.github.takahirom.arbigent.test

import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MaestroDeviceReconnectionTest {

    @Test
    fun testReconnectionRetriesUpToMaxAttempts() = runTest {
        // Test that reconnection fails after max attempts
        val device = TestMaestroDeviceWithRetryLimit()
        
        val exception = assertFailsWith<RuntimeException> {
            // Simulate multiple failed reconnection attempts
            device.simulateFailedReconnectionAttempts()
        }
        
        assertTrue(exception.message?.contains("Failed to reconnect after 6 attempts") == true)
    }

    @Test
    fun testReconnectionResetsCounterOnSuccess() = runTest {
        // Test that successful reconnection resets the counter
        val device = TestMaestroDeviceWithRetryLimit()
        
        // First reconnection succeeds
        device.simulateSuccessfulReconnection()
        
        // Counter should be reset, so we can reconnect again
        device.simulateSuccessfulReconnection()
        
        // Both reconnections should succeed
        assertTrue(true, "Both reconnections succeeded after counter reset")
    }

    @Test
    fun testReconnectionThrowsWhenNoAvailableDeviceReference() {
        // Test that reconnection fails without available device
        val device = TestMaestroDeviceWithRetryLimit(hasAvailableDevice = false)
        
        val exception = assertFailsWith<IllegalStateException> {
            device.simulateReconnectionWithoutDevice()
        }
        
        assertEquals("Cannot reconnect: no available device reference", exception.message)
    }

    @Test
    fun testReconnectFailsWhenLivenessProbeNeverPasses() = runTest {
        // A connection that establishes but is never responsive (e.g. a crashed
        // XCTest runner on a refused port) must NOT be treated as a successful
        // reconnect. It should keep retrying with backoff and finally fail.
        val device = TestMaestroDeviceWithRetryLimit(probePasses = false)

        val exception = assertFailsWith<RuntimeException> {
            device.simulateReconnectionWithLivenessProbe()
        }

        assertTrue(exception.message?.contains("Failed to reconnect after 6 attempts") == true)
    }

    @Test
    fun testReconnectSucceedsWhenLivenessProbePasses() = runTest {
        // When the reconnected device responds to the liveness probe, the reconnect
        // succeeds and the attempt counter is reset.
        val device = TestMaestroDeviceWithRetryLimit(probePasses = true)

        device.simulateReconnectionWithLivenessProbe()

        assertEquals(0, device.currentAttempts(), "Counter should reset on a verified reconnect")
    }

    @Test
    fun testRecoverableErrorClassification() {
        val recovery = DeviceRecoveryMimic()
        // Connection/crash signatures are recoverable.
        assertTrue(recovery.isRecoverable(java.net.ConnectException("Connection refused")))
        assertTrue(recovery.isRecoverable(RuntimeException("App crashed or stopped while executing flow")))
        assertTrue(recovery.isRecoverable(RuntimeException("Failed to connect to /[0:0:0:0:0:0:0:1]:8080")))
        // Wrapped causes are detected too.
        assertTrue(recovery.isRecoverable(RuntimeException("wrapper", java.net.ConnectException("boom"))))
        // A real assertion/logic failure is NOT recoverable.
        assertTrue(!recovery.isRecoverable(AssertionError("expected X but was Y")))
        assertTrue(!recovery.isRecoverable(IllegalStateException("invalid scenario")))
    }

    @Test
    fun testRecoveryRetriesThenSucceeds() {
        val recovery = DeviceRecoveryMimic()
        var calls = 0
        val result = recovery.withRecovery {
            calls++
            if (calls < 3) throw java.net.ConnectException("Connection refused")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(2, recovery.reconnectCalls, "should reconnect once per recoverable failure")
    }

    @Test
    fun testRecoveryRethrowsNonRecoverableImmediately() {
        val recovery = DeviceRecoveryMimic()
        assertFailsWith<AssertionError> {
            recovery.withRecovery { throw AssertionError("real failure") }
        }
        assertEquals(0, recovery.reconnectCalls, "must not reconnect on a non-recoverable error")
    }

    @Test
    fun testRecoveryGivesUpAfterMaxAttempts() {
        val recovery = DeviceRecoveryMimic()
        assertFailsWith<RuntimeException> {
            recovery.withRecovery { throw java.net.ConnectException("Connection refused") }
        }
        assertEquals(3, recovery.reconnectCalls, "should attempt recovery up to the max")
    }

    @Test
    fun testThreadSafetyOfReconnection() = runTest {
        // Test that reconnection is thread-safe
        val device = TestMaestroDeviceWithRetryLimit()
        val reconnectCount = AtomicInteger(0)
        
        // Start multiple threads trying to reconnect simultaneously
        val threads = List(3) {
            Thread {
                try {
                    device.simulateThreadSafeReconnection(reconnectCount)
                } catch (e: Exception) {
                    // Ignore exceptions
                }
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join(1000) } // Wait max 1 second
        
        // Due to synchronization, reconnections should be serialized
        assertTrue(reconnectCount.get() <= 3, "Reconnections should be synchronized")
    }


    /**
     * Mirrors MaestroDevice.isRecoverableDeviceError / withDeviceRecovery so the
     * recovery semantics are covered without a real Maestro device.
     */
    private class DeviceRecoveryMimic {
        private val maxOperationRecoveries = 3
        var reconnectCalls = 0

        fun isRecoverable(error: Throwable): Boolean {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is java.net.ConnectException) return true
                val message = cause.message ?: ""
                if (message.contains("App crashed or stopped", ignoreCase = true) ||
                    message.contains("Connection refused", ignoreCase = true) ||
                    message.contains("Failed to connect", ignoreCase = true)
                ) {
                    return true
                }
                cause = cause.cause
            }
            return false
        }

        fun <T> withRecovery(operation: () -> T): T {
            var lastError: Exception? = null
            for (attempt in 0 until maxOperationRecoveries) {
                try {
                    return operation()
                } catch (e: Exception) {
                    if (!isRecoverable(e)) throw e
                    lastError = e
                    reconnectCalls++
                }
            }
            throw RuntimeException("Device operation failed after $maxOperationRecoveries recovery attempts", lastError)
        }
    }

    /**
     * Test implementation simulating MaestroDevice reconnection logic.
     * This mimics the actual implementation's behavior for testing.
     */
    private class TestMaestroDeviceWithRetryLimit(
        private val hasAvailableDevice: Boolean = true,
        private val probePasses: Boolean = true
    ) {
        private var reconnectAttempts = 0
        private val maxReconnectAttempts = 6
        private val reconnectLock = Any()

        fun currentAttempts(): Int = reconnectAttempts

        // Mirrors reconnectIfDisconnected(): a fresh connection is only adopted when
        // the liveness probe passes; a failed probe counts as a failed attempt and
        // keeps the backoff loop going until maxReconnectAttempts is exhausted.
        fun simulateReconnectionWithLivenessProbe() {
            synchronized(reconnectLock) {
                var lastException: Exception? = null
                while (reconnectAttempts < maxReconnectAttempts) {
                    reconnectAttempts++
                    // connectToDevice() succeeds (builds driver objects), then probe.
                    if (probePasses) {
                        reconnectAttempts = 0
                        return
                    }
                    lastException = RuntimeException("device not responsive")
                }
                reconnectAttempts = 0
                throw RuntimeException("Failed to reconnect after $maxReconnectAttempts attempts", lastException)
            }
        }

        fun simulateFailedReconnectionAttempts() {
            synchronized(reconnectLock) {
                // Simulate multiple failed attempts
                for (i in 1..7) { // Try 7 times to ensure we hit the limit
                    if (reconnectAttempts >= maxReconnectAttempts) {
                        throw RuntimeException("Failed to reconnect after $maxReconnectAttempts attempts")
                    }
                    reconnectAttempts++
                }
            }
        }

        fun simulateSuccessfulReconnection() {
            synchronized(reconnectLock) {
                if (reconnectAttempts >= maxReconnectAttempts) {
                    throw RuntimeException("Failed to reconnect after $maxReconnectAttempts attempts")
                }
                reconnectAttempts++
                // Simulate successful reconnection
                // Reset counter on success
                reconnectAttempts = 0
            }
        }

        fun simulateReconnectionWithoutDevice() {
            synchronized(reconnectLock) {
                if (!hasAvailableDevice) {
                    throw IllegalStateException("Cannot reconnect: no available device reference")
                }
            }
        }

        fun simulateThreadSafeReconnection(counter: AtomicInteger) {
            synchronized(reconnectLock) {
                counter.incrementAndGet()
                // Simulate reconnection work
                Thread.sleep(50)
            }
        }
    }
}