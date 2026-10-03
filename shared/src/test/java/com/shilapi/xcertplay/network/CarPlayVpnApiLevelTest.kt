package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayVpnApiLevelTest {
    @Test fun kitKatUsesLegacyVpnBuilderPath() {
        assertFalse(useScopedVpnBuilder(19))
        assertFalse(useScopedVpnBuilder(20))
    }

    @Test fun lollipopAndNewerUseScopedVpnBuilderPath() {
        assertTrue(useScopedVpnBuilder(21))
        assertTrue(useScopedVpnBuilder(28))
        assertTrue(useScopedVpnBuilder(33))
    }
}
