package com.shilapi.xcertplay.crvsim;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Records both directions and serializes checked failures as replayable FAULT events. */
public final class CrvRecordingExchange implements CrvExchange {
    private final CrvExchange delegate;
    private final CrvTrace.Recorder recorder;

    public CrvRecordingExchange(CrvExchange delegate, CrvTrace.Recorder recorder) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override public byte[] transact(
            CrvTrace.Layer layer, String channel, byte[] request) throws IOException {
        recorder.record(
                layer, CrvTrace.Direction.HOST_TO_DEVICE, channel, request, Collections.emptyMap());
        try {
            byte[] response = delegate.transact(layer, channel, request);
            recorder.record(
                    layer, CrvTrace.Direction.DEVICE_TO_HOST, channel, response, Collections.emptyMap());
            return response;
        } catch (IOException error) {
            recorder.record(layer, CrvTrace.Direction.FAULT, channel, new byte[0], fault(error));
            throw error;
        }
    }

    @Override public Map<String, String> awaitSignal(
            CrvTrace.Layer layer, String channel) throws IOException {
        try {
            Map<String, String> signal = delegate.awaitSignal(layer, channel);
            recorder.record(layer, CrvTrace.Direction.SIGNAL, channel, new byte[0], signal);
            return signal;
        } catch (IOException error) {
            recorder.record(layer, CrvTrace.Direction.FAULT, channel, new byte[0], fault(error));
            throw error;
        }
    }

    private static Map<String, String> fault(IOException error) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("exception", error.getClass().getName());
        attributes.put("message", error.getMessage() == null ? "" : error.getMessage());
        return attributes;
    }
}
