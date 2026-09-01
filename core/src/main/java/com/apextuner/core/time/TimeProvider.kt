package com.apextuner.core.time

interface TimeProvider {
    fun nowEpochMillis(): Long
}

object SystemTimeProvider : TimeProvider {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
