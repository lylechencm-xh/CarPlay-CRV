package com.shilapi.xcertplay.crvsim;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Strict playback: every request, layer, channel and byte must match the recording. */
public final class CrvReplayExchange implements CrvExchange {
    private final List<CrvTrace.Event> events;
    private int cursor;

    public CrvReplayExchange(CrvTrace trace) {
        this.events = trace.events();
    }

    @Override public synchronized byte[] transact(
            CrvTrace.Layer layer, String channel, byte[] request) throws IOException {
        CrvTrace.Event sent = next("host request");
        requireMatch(sent, layer, CrvTrace.Direction.HOST_TO_DEVICE, channel, request);
        CrvTrace.Event result = next("device response");
        requireLayerAndChannel(result, layer, channel);
        if (result.direction() == CrvTrace.Direction.FAULT) throw recordedFault(result);
        if (result.direction() != CrvTrace.Direction.DEVICE_TO_HOST) {
            throw mismatch("expected DEVICE_TO_HOST or FAULT but found " + result);
        }
        return result.payload();
    }

    @Override public synchronized Map<String, String> awaitSignal(
            CrvTrace.Layer layer, String channel) throws IOException {
        CrvTrace.Event event = next("signal");
        requireLayerAndChannel(event, layer, channel);
        if (event.direction() == CrvTrace.Direction.FAULT) throw recordedFault(event);
        if (event.direction() != CrvTrace.Direction.SIGNAL) {
            throw mismatch("expected SIGNAL or FAULT but found " + event);
        }
        return event.attributes();
    }

    public synchronized boolean isExhausted() { return cursor == events.size(); }
    public synchronized int remainingEvents() { return events.size() - cursor; }

    private CrvTrace.Event next(String operation) throws ReplayMismatchException {
        if (cursor >= events.size()) {
            throw mismatch("trace ended while waiting for " + operation + " at event " + cursor);
        }
        return events.get(cursor++);
    }

    private static void requireMatch(
            CrvTrace.Event event,
            CrvTrace.Layer layer,
            CrvTrace.Direction direction,
            String channel,
            byte[] payload
    ) throws ReplayMismatchException {
        requireLayerAndChannel(event, layer, channel);
        if (event.direction() != direction) {
            throw mismatch("direction mismatch: expected " + direction + " but found " + event.direction());
        }
        if (!Arrays.equals(event.payload(), payload)) {
            throw mismatch("payload mismatch at event " + event.index() + " layer=" + layer +
                    " channel=" + channel);
        }
    }

    private static void requireLayerAndChannel(
            CrvTrace.Event event,
            CrvTrace.Layer layer,
            String channel
    ) throws ReplayMismatchException {
        if (event.layer() != layer || !event.channel().equals(channel)) {
            throw mismatch("route mismatch: expected " + layer + '/' + channel + " but found " +
                    event.layer() + '/' + event.channel() + " at event " + event.index());
        }
    }

    private static IOException recordedFault(CrvTrace.Event event) {
        String message = event.attributes().getOrDefault("message", "recorded transport failure");
        String type = event.attributes().getOrDefault("exception", IOException.class.getName());
        return new RecordedFaultException(type + ": " + message, event);
    }

    private static ReplayMismatchException mismatch(String message) {
        return new ReplayMismatchException(message);
    }

    public static final class ReplayMismatchException extends IOException {
        public ReplayMismatchException(String message) { super(message); }
    }

    public static final class RecordedFaultException extends IOException {
        private final CrvTrace.Event event;

        public RecordedFaultException(String message, CrvTrace.Event event) {
            super(message);
            this.event = event;
        }

        public CrvTrace.Event event() { return event; }
    }
}
