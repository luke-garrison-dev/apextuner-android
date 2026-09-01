package com.apextuner.feature.settings.automation

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTimingTest {
    @Test fun schedulesNextLocalTargetAcrossAutumnDstTransition() {
        val zone = ZoneId.of("Europe/Rome")
        val before = ZonedDateTime.of(2026, 10, 24, 21, 30, 0, 0, zone)
        val beforeTarget = Instant.ofEpochMilli(AutomationTiming.nextLocalEpochMillis(before, 22)).atZone(zone)
        assertEquals(before.toLocalDate(), beforeTarget.toLocalDate())
        assertEquals(22, beforeTarget.hour)

        val after = ZonedDateTime.of(2026, 10, 24, 22, 30, 0, 0, zone)
        val target = Instant.ofEpochMilli(AutomationTiming.nextLocalEpochMillis(after, 22)).atZone(zone)
        assertEquals(2026, target.year)
        assertEquals(10, target.monthValue)
        assertEquals(25, target.dayOfMonth)
        assertEquals(22, target.hour)
    }

    @Test fun successfulDailyRunRealignsToWallClockAcrossSpringDstTransition() {
        val zone = ZoneId.of("Europe/Rome")
        val run = ZonedDateTime.of(2027, 3, 27, 22, 30, 0, 0, zone)
        val target = Instant.ofEpochMilli(AutomationTiming.nextLocalEpochMillisAfterDays(run, 22, 1)).atZone(zone)
        assertEquals(2027, target.year)
        assertEquals(3, target.monthValue)
        assertEquals(28, target.dayOfMonth)
        assertEquals(22, target.hour)
        assertTrue(target.offset != run.offset)
    }

    @Test fun weeklyMaintenanceRetainsSevenDayCadenceAtLocalHour() {
        val zone = ZoneId.of("Europe/Rome")
        val run = ZonedDateTime.of(2026, 10, 24, 3, 20, 0, 0, zone)
        val target = Instant.ofEpochMilli(AutomationTiming.nextLocalEpochMillisAfterDays(run, 3, 7)).atZone(zone)
        assertEquals(run.toLocalDate().plusDays(7), target.toLocalDate())
        assertEquals(3, target.hour)
    }
}
