package com.shilapi.xcertplay.crvsim;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** Line-oriented codec designed for stable diffs, archival and corruption detection. */
public final class CrvTraceCodec {
    private CrvTraceCodec() {}

    public static void write(Path path, CrvTrace trace) throws IOException {
        Files.write(path, encode(trace).getBytes(StandardCharsets.UTF_8));
    }

    public static CrvTrace read(Path path) throws IOException {
        return decode(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    public static String encode(CrvTrace trace) {
        StringBuilder output = new StringBuilder(CrvTrace.HEADER).append('\n');
        for (CrvTrace.Event event : trace.events()) {
            String attributes = encodeAttributes(event.attributes());
            output.append("E\t")
                    .append(event.index()).append('\t')
                    .append(event.offsetMicros()).append('\t')
                    .append(event.layer()).append('\t')
                    .append(event.direction()).append('\t')
                    .append(base64(event.channel().getBytes(StandardCharsets.UTF_8))).append('\t')
                    .append(base64(attributes.getBytes(StandardCharsets.UTF_8))).append('\t')
                    .append(base64(event.payload())).append('\t')
                    .append(Long.toHexString(checksum(event)))
                    .append('\n');
        }
        return output.toString();
    }

    public static CrvTrace decode(String encoded) throws IOException {
        String[] lines = encoded.split("\\R", -1);
        if (lines.length == 0 || !CrvTrace.HEADER.equals(lines[0])) {
            throw new TraceFormatException("unsupported trace header");
        }
        List<CrvTrace.Event> events = new ArrayList<>();
        for (int lineNumber = 1; lineNumber < lines.length; lineNumber++) {
            String line = lines[lineNumber];
            if (line.isEmpty()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 9 || !"E".equals(fields[0])) {
                throw new TraceFormatException("invalid event at line " + (lineNumber + 1));
            }
            try {
                CrvTrace.Event event = new CrvTrace.Event(
                        Long.parseLong(fields[1]),
                        Long.parseLong(fields[2]),
                        CrvTrace.Layer.valueOf(fields[3]),
                        CrvTrace.Direction.valueOf(fields[4]),
                        new String(unbase64(fields[5]), StandardCharsets.UTF_8),
                        unbase64(fields[7]),
                        decodeAttributes(new String(unbase64(fields[6]), StandardCharsets.UTF_8)));
                long expected = Long.parseUnsignedLong(fields[8], 16);
                long actual = checksum(event);
                if (expected != actual) {
                    throw new TraceFormatException("CRC mismatch at event " + event.index());
                }
                events.add(event);
            } catch (TraceFormatException error) {
                throw error;
            } catch (IllegalArgumentException error) {
                throw new TraceFormatException("invalid event at line " + (lineNumber + 1), error);
            }
        }
        return new CrvTrace(events);
    }

    private static long checksum(CrvTrace.Event event) {
        CRC32 crc = new CRC32();
        update(crc, event.layer().name().getBytes(StandardCharsets.US_ASCII));
        update(crc, event.direction().name().getBytes(StandardCharsets.US_ASCII));
        update(crc, event.channel().getBytes(StandardCharsets.UTF_8));
        update(crc, encodeAttributes(event.attributes()).getBytes(StandardCharsets.UTF_8));
        update(crc, event.payload());
        return crc.getValue();
    }

    private static void update(CRC32 crc, byte[] bytes) {
        crc.update(bytes, 0, bytes.length);
        crc.update(0);
    }

    private static String encodeAttributes(Map<String, String> attributes) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(attributes.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder value = new StringBuilder();
        for (Map.Entry<String, String> entry : entries) {
            if (value.length() > 0) value.append('&');
            value.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            value.append('=');
            value.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return value.toString();
    }

    private static Map<String, String> decodeAttributes(String encoded) {
        if (encoded.isEmpty()) return Collections.emptyMap();
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String pair : encoded.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator < 0) throw new IllegalArgumentException("invalid attribute");
            attributes.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return attributes;
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] unbase64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    public static final class TraceFormatException extends IOException {
        public TraceFormatException(String message) { super(message); }
        public TraceFormatException(String message, Throwable cause) { super(message, cause); }
    }
}
