package com.shilapi.xcertplay.crvsim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class CrvSimulatorTest {
    @Test
    public void recordsAndReplaysACompleteVirtualCrvSession() throws Exception {
        RecordedSession recorded = record(CrvScriptedExchange.successfulHardware());
        assertTrue(recorded.report.isActive());
        recorded.trace.requireFullCrvCoverage();
        assertEquals(EnumSet.allOf(CrvTrace.Layer.class), recorded.trace.coveredLayers());

        String serialized = CrvTraceCodec.encode(recorded.trace);
        CrvTrace decoded = CrvTraceCodec.decode(serialized);
        CrvReplayExchange replay = new CrvReplayExchange(decoded);
        VirtualCrvClient.Report replayReport = new VirtualCrvClient(replay).connect();

        assertTrue(replayReport.isActive());
        assertTrue(replay.isExhausted());
        assertEquals(recorded.report.state(), replayReport.state());
    }

    @Test
    public void recordsAndReplaysPeerEofDuringCarPlaySetup() throws Exception {
        CrvScriptedExchange hardware = CrvScriptedExchange.successfulHardware()
                .onTransaction(CrvTrace.Layer.CARPLAY_SESSION, "rtsp-setup", request -> {
                    throw new IOException("peer EOF while waiting for SETUP response");
                });
        RecordedSession recorded = record(hardware);

        assertFalse(recorded.report.isActive());
        assertEquals(CrvTrace.Layer.CARPLAY_SESSION, recorded.report.failedLayer());
        assertEquals(VirtualCrvClient.State.MFI_READY, recorded.report.lastStableState());
        assertTrue(recorded.trace.events().stream()
                .anyMatch(event -> event.direction() == CrvTrace.Direction.FAULT));

        CrvReplayExchange replay = new CrvReplayExchange(recorded.trace);
        VirtualCrvClient.Report replayReport = new VirtualCrvClient(replay).connect();

        assertFalse(replayReport.isActive());
        assertEquals(CrvTrace.Layer.CARPLAY_SESSION, replayReport.failedLayer());
        assertTrue(replayReport.message().contains("peer EOF"));
        assertTrue(replay.isExhausted());
    }

    @Test
    public void recordsAndReplaysAsynchronousSignals() throws Exception {
        CrvScriptedExchange hardware = new CrvScriptedExchange()
                .onSignal(CrvTrace.Layer.NCM, "link-state", () -> Collections.singletonMap("state", "up"));
        CrvTrace.Recorder recorder = new CrvTrace.Recorder();
        CrvRecordingExchange recording = new CrvRecordingExchange(hardware, recorder);
        assertEquals("up", recording.awaitSignal(CrvTrace.Layer.NCM, "link-state").get("state"));

        CrvReplayExchange replay = new CrvReplayExchange(recorder.snapshot());
        assertEquals("up", replay.awaitSignal(CrvTrace.Layer.NCM, "link-state").get("state"));
        assertTrue(replay.isExhausted());
    }

    @Test
    public void strictReplayRejectsRequestDrift() throws Exception {
        RecordedSession recorded = record(CrvScriptedExchange.successfulHardware());
        CrvReplayExchange replay = new CrvReplayExchange(recorded.trace);
        CrvReplayExchange.ReplayMismatchException failure = assertThrows(
                CrvReplayExchange.ReplayMismatchException.class,
                () -> replay.transact(CrvTrace.Layer.USB, "role-switch", new byte[] {0x00}));
        assertTrue(failure.getMessage().contains("payload"));
    }

    @Test
    public void traceCodecRejectsPayloadCorruption() throws Exception {
        RecordedSession recorded = record(CrvScriptedExchange.successfulHardware());
        String serialized = CrvTraceCodec.encode(recorded.trace);
        String[] lines = serialized.split("\\n", -1);
        String[] fields = lines[1].split("\\t", -1);
        fields[7] = "AA";
        lines[1] = String.join("\t", fields);
        String corrupted = String.join("\n", lines);
        CrvTraceCodec.TraceFormatException failure = assertThrows(
                CrvTraceCodec.TraceFormatException.class,
                () -> CrvTraceCodec.decode(corrupted));
        assertTrue(failure.getMessage().contains("CRC"));
    }

    private static RecordedSession record(CrvScriptedExchange hardware) {
        AtomicLong clock = new AtomicLong(1_000L);
        CrvTrace.Recorder recorder = new CrvTrace.Recorder(clock::getAndIncrement);
        VirtualCrvClient.Report report = new VirtualCrvClient(
                new CrvRecordingExchange(hardware, recorder)).connect();
        return new RecordedSession(report, recorder.snapshot());
    }

    private static final class RecordedSession {
        private final VirtualCrvClient.Report report;
        private final CrvTrace trace;

        private RecordedSession(VirtualCrvClient.Report report, CrvTrace trace) {
            this.report = report;
            this.trace = trace;
        }
    }
}
