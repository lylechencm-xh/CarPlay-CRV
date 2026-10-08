package com.shilapi.xcertplay.crvsim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class CrvProductionLogImporterTest {
    @Test
    public void importsAndReplaysSanitizedSetupEofFixture() throws Exception {
        CrvTrace trace = CrvProductionLogImporter.decode(fixture());
        trace.requireFullCrvCoverage();

        assertEquals(9, trace.events().size());
        assertEquals(
                "503",
                trace.events().get(7).attributes().get("byteCount"));
        assertFalse(trace.events().get(7).attributes().containsKey("payload"));

        CrvProductionTraceReplay.Report report = new CrvProductionTraceReplay().replay(trace);
        assertEquals(
                CrvProductionTraceReplay.Outcome.RECOVERY_REQUIRED,
                report.outcome());
        assertEquals(CrvTrace.Layer.CARPLAY_SESSION, report.terminalLayer());
        assertEquals(9, report.consumedEvents());
        assertEquals("peer-eof-before-active", report.message());
    }

    private static String fixture() throws IOException {
        String name = "/fixtures/crv-2021-setup-eof.production.txt";
        try (InputStream input = CrvProductionLogImporterTest.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing fixture " + name);
            byte[] buffer = new byte[4096];
            StringBuilder text = new StringBuilder();
            int count;
            while ((count = input.read(buffer)) != -1) {
                text.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
            }
            return text.toString();
        }
    }
}
