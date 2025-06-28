package io.github.takahirom.arbigent.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Utility functions for managing logging configuration.
 */
object LoggingUtils {
    
    /**
     * Suppresses SLF4J initialization warnings by temporarily redirecting stderr
     * during SLF4J initialization.
     */
    fun suppressSlf4jWarnings() {
        val originalErr = System.err
        val nullStream = PrintStream(ByteArrayOutputStream())
        
        try {
            // Set properties first
            System.setProperty("slf4j.internal.verbosity", "WARN")
            
            // Temporarily redirect stderr to suppress SLF4J initialization warnings
            System.setErr(nullStream)
            
            // Force SLF4J initialization by attempting to get a logger
            try {
                org.slf4j.LoggerFactory.getLogger("init")
            } catch (_: Exception) {
                // Ignore any exceptions during logger initialization
            }
            
            // Restore stderr after SLF4J initialization
            System.setErr(originalErr)
        } catch (e: Exception) {
            // Ensure stderr is restored even if an exception occurs
            System.setErr(originalErr)
            throw e
        }
    }
}