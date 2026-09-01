package com.apextuner.feature.network.firewall

import java.io.Closeable

/**
 * Owns at most one closeable resource and makes replacement/cleanup race-safe.
 * Closing an obsolete resource never clears or closes a newer replacement.
 */
internal class CloseableResourceSlot<T : Closeable> {
    private val lock = Any()
    private var current: T? = null

    fun replace(value: T) {
        val previous = synchronized(lock) {
            val old = current
            current = value
            old
        }
        if (previous !== value) previous.closeQuietly()
    }

    fun currentOrNull(): T? = synchronized(lock) { current }

    fun close(expected: T? = null) {
        val target = synchronized(lock) {
            if (expected == null) {
                val value = current
                current = null
                value
            } else if (current === expected) {
                current = null
                expected
            } else {
                // A newer replacement (or a previous global close) owns lifecycle now.
                // Do not double-close a stale descriptor from an obsolete reader.
                null
            }
        }
        target.closeQuietly()
    }

    private fun T?.closeQuietly() {
        if (this != null) runCatching { close() }
    }
}
