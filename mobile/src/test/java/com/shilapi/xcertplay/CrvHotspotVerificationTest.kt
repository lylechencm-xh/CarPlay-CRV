package com.shilapi.xcertplay

import java.util.BitSet
import org.junit.Assert.*
import org.junit.Test

class CrvHotspotVerificationTest {
    private fun config(ssid: String = "vehicle", secret: String? = "password", secured: Boolean = true,
                       key: Int = 1) = CrvHotspotConfiguration(ssid, false, secret, secured,
        listOf(BitSet().apply { set(key) }))

    @Test fun readbackMustMatchIdentitySecurityAndVisiblePassword() {
        val saved = config()
        assertTrue(saved.matches(config(secret = "\"password\"")))
        assertFalse(saved.matches(config(ssid = "temporary")))
        assertFalse(saved.matches(config(secret = "different")))
        assertFalse(saved.matches(config(key = 2)))
        assertFalse(saved.matches(config(secret = "*")))
        assertFalse(config(secret = "*").canVerify)
        assertTrue(config(secret = null, secured = false).matches(config(secret = null, secured = false)))
    }

    @Test fun slowFrameworkStopCanCompleteAfterFiveSeconds() {
        var time = 0L
        assertTrue(awaitCrvCondition(8_000, { time }, { time += it }) { time >= 5_500 })
        assertEquals(5_500, time)
    }

    @Test fun timeoutCannotClaimSuccessfulTransition() {
        var time = 0L
        assertFalse(awaitCrvCondition(250, { time }, { time += it }) { false })
        assertEquals(250, time)
    }
}
