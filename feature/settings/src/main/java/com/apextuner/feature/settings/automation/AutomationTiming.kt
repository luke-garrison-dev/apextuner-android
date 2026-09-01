package com.apextuner.feature.settings.automation

import java.time.ZonedDateTime

internal object AutomationTiming {
    fun nextLocalEpochMillis(now: ZonedDateTime, hour: Int): Long =
        nextLocalTarget(now, hour).toInstant().toEpochMilli()

    fun nextLocalEpochMillisAfterDays(now: ZonedDateTime, hour: Int, daysAfter: Int): Long {
        require(hour in 0..23)
        require(daysAfter >= 1)
        return now.toLocalDate()
            .plusDays(daysAfter.toLong())
            .atTime(hour, 0)
            .atZone(now.zone)
            .toInstant()
            .toEpochMilli()
    }

    private fun nextLocalTarget(now: ZonedDateTime, hour: Int): ZonedDateTime {
        require(hour in 0..23)
        var target = now.toLocalDate().atTime(hour, 0).atZone(now.zone)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target
    }
}
