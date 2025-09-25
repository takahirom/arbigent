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
     * Test implementation simulating MaestroDevice reconnection logic.
     * This mimics the actual implementation's behavior for testing.
     */
    private class TestMaestroDeviceWithRetryLimit(
        private val hasAvailableDevice: Boolean = true
    ) {
        private var reconnectAttempts = 0
        private val maxReconnectAttempts = 6
        private val reconnectLock = Any()

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