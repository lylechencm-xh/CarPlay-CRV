package com.shilapi.xcertplay.crvsim;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Imports sanitized CRVTRACE records embedded in an Android field log. */
public final class CrvProductionLogImporter {
    private static final String MARKER = "CRVTRACE\t1\t";

    private CrvProductionLogImporter() {}

    public static CrvTrace read(Path path) throws IOException {
        return decode(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    public static CrvTrace decode(String log) throws IOException {
        List<CrvTrace.Event> events = new ArrayList<>();
        String[] lines = log.split("\\R", -1);
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = lines[lineNumber];
            int marker = line.indexOf(MARKER);
            if (marker < 0) continue;
            String[] fields = line.substring(marker).split("\\t", -1);
            if (fields.length != 9 || !"CRVTRACE".equals(fields[0]) || !"1".equals(fields[1])) {
                throw new IOException("invalid production trace at log line " + (lineNumber + 1));
            }
            try {
                long sourceSequence = Long.parseLong(fields[2]);
                long offsetMicros = Math.multiplyExact(Long.parseLong(fields[3]), 1_000L);
                int byteCount = Integer.parseInt(fields[7]);
                if (sourceSequence < 0 || byteCount < 0) {
                    throw new IllegalArgumentException("negative trace field");
                }
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("sourceSequence", Long.toString(sourceSequence));
                attributes.put("byteCount", Integer.toString(byteCount));
                String detail = decodeField(fields[8]);
                if (!detail.isEmpty()) attributes.put("detail", detail);
                events.add(new CrvTrace.Event(
                        events.size(),
                        offsetMicros,
                        CrvTrace.Layer.valueOf(fields[4]),
                        CrvTrace.Direction.valueOf(fields[5]),
                        decodeField(fields[6]),
                        new byte[0],
                        attributes));
            } catch (ArithmeticException | IllegalArgumentException error) {
                throw new IOException(
                        "invalid production trace at log line " + (lineNumber + 1), error);
            }
        }
        if (events.isEmpty()) throw new IOException("field log contains no CRVTRACE records");
        ensureMonotonicOffsets(events);
        return new CrvTrace(events);
    }

    private static String decodeField(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void ensureMonotonicOffsets(List<CrvTrace.Event> events) throws IOException {
        long previous = -1;
        for (CrvTrace.Event event : events) {
            if (event.offsetMicros() < previous) {
                throw new IOException("production trace offsets are not monotonic");
            }
            previous = event.offsetMicros();
        }
    }
}
