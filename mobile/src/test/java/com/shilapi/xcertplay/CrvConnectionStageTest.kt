package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CrvConnectionStageTest {
    @Test
    fun receiveStatisticsRemainDiagnosticOnlyAndAreNotErrors() {
        val message = "Receive: video packets=6 bytes=768 " +
            "seqMissingAfterReadOver250Ms=0"

        assertEquals(true, isCrvUiDiagnostic(message))
        assertEquals(false, isCrvConnectionError(message))
    }

    @Test
    fun explicitMissingMfiIdentityRemainsAnError() {
        assertEquals(
            true,
            isCrvConnectionError(
                "MFi identity missing; transport pre-auth verified",
            ),
        )
    }

    @Test
    fun nonfatalUnavailableDiagnosticsDoNotReplaceTheCarPlayUi() {
        assertEquals(
            false,
            isCrvConnectionError("Microphone: voice source unavailable; using MIC"),
        )
    }

    @Test
    fun handshakeProgressAndRecoveredNcmFallbackAreNotErrors() {
        assertEquals(false, isCrvConnectionError("iap2 awaiting AirPlay session timeoutMs=60000"))
        assertEquals(false, isCrvConnectionError("iap2 awaiting CarPlay availability timeoutMs=60000"))
        assertEquals(false, isCrvConnectionError("Controller state STOPPING -> STOPPED reason=stopped"))
        assertEquals(false, isCrvConnectionError("Honda CDC-NCM fallback blocked classification=usb-interface-resource-conflict"))
        assertEquals(false, isCrvConnectionError("Honda CDC-NCM fallback escalating policy=PRESERVE_KERNEL_DRIVER -> DETACH_KERNEL_DRIVER"))
    }

    @Test
    fun actualHandshakeTimeoutAndUsbDetachRemainErrors() {
        assertEquals(
            true,
            isCrvConnectionError("iAP2 wired control ended terminal=TIMED_OUT stage=CARPLAY_START_SENT"),
        )
        assertEquals(true, isCrvConnectionError("CarPlay handshake timed out stage=CARPLAY_START_SENT"))
        assertEquals(true, isCrvConnectionError("iPhone USB detached during NCM read"))
    }

    @Test
    fun lowLevelIap2MessagesDoNotRegressAirPlayProgress() {
        assertEquals(
            CrvConnectionStage.AIRPLAY_LISTENING,
            monotonicConnectionStage(
                CrvConnectionStage.AIRPLAY_LISTENING,
                CrvConnectionStage.IAP2,
            ),
        )
    }

    @Test
    fun sameRankCanRefineAirPlayState() {
        assertEquals(
            CrvConnectionStage.AIRPLAY_CONNECTED,
            monotonicConnectionStage(
                CrvConnectionStage.AIRPLAY_LISTENING,
                CrvConnectionStage.AIRPLAY_CONNECTED,
            ),
        )
    }

    @Test
    fun errorsAndRetriesRemainVisible() {
        assertEquals(
            CrvConnectionStage.ERROR,
            monotonicConnectionStage(
                CrvConnectionStage.AIRPLAY_CONNECTED,
                CrvConnectionStage.ERROR,
            ),
        )
        assertEquals(
            CrvConnectionStage.RETRYING,
            monotonicConnectionStage(
                CrvConnectionStage.AIRPLAY_CONNECTED,
                CrvConnectionStage.RETRYING,
            ),
        )
    }
}
