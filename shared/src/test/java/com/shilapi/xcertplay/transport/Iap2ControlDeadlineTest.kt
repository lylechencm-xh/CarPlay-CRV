package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class Iap2ControlDeadlineTest {
    @Test fun activeUnlimitedDriveSurvivesFiveMinutesAndAFullDay() {
        var now = 0L
        var active = false
        val deadline = Iap2ControlDeadline(Long.MAX_VALUE, { now }, { active })
        deadline.authenticated()
        active = true
        for (minutes in listOf(6L, 90L, 25L * 60L)) {
            now = minutes * 60_000_000_000L
            assertEquals(30_000L, deadline.remainingMillis())
        }
    }

    @Test fun unlimitedDriveDoesNotLeaveAnUnauthenticatedHandshakeHanging() {
        var now = 0L
        val deadline = Iap2ControlDeadline(Long.MAX_VALUE, clockNanos = { now })
        now = 59_999_000_000L
        assertEquals(1L, deadline.remainingMillis())
        now = 60_000_000_000L
        assertEquals(0L, deadline.remainingMillis())
    }

    @Test fun authenticatedDriveTimesOutWhileWaitingForCarPlayAvailability() {
        var now = 10_000_000_000L
        val deadline = Iap2ControlDeadline(Long.MAX_VALUE, clockNanos = { now })
        deadline.authenticated()
        now += 60_000_000_000L
        assertEquals(0L, deadline.remainingMillis())
    }

    @Test fun startSessionResetsTheWindowUntilAirPlayBecomesActive() {
        var now = 0L
        var active = false
        val deadline = Iap2ControlDeadline(Long.MAX_VALUE, { now }, { active })
        deadline.authenticated()
        now = 55_000_000_000L
        deadline.carPlayStartSent()
        now = 114_999_000_000L
        assertEquals(1L, deadline.remainingMillis())
        active = true
        assertEquals(30_000L, deadline.remainingMillis())
        active = false
        now = 300_000_000_000L
        assertEquals(30_000L, deadline.remainingMillis())
    }

    @Test fun repeatedAvailabilityDoesNotExtendTheAirPlayStartupWindow() {
        var now = 0L
        val deadline = Iap2ControlDeadline(Long.MAX_VALUE, clockNanos = { now })
        deadline.authenticated()
        now = 55_000_000_000L
        deadline.carPlayStartSent()
        now = 60_000_000_000L
        deadline.carPlayStartSent()
        now = 115_000_000_000L
        assertEquals(0L, deadline.remainingMillis())
    }

    @Test fun explicitFiniteWindowsStillExpireAfterAuthentication() {
        var now = -1_000_000_000L
        val deadline = Iap2ControlDeadline(100, clockNanos = { now })
        deadline.authenticated()
        now += 100_000_000L
        assertEquals(0L, deadline.remainingMillis())
    }
}
