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
