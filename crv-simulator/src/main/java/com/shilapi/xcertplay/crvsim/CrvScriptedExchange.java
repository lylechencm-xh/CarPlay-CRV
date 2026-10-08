package com.shilapi.xcertplay.crvsim;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** In-memory fake used to create recordings and deterministic fault fixtures. */
public final class CrvScriptedExchange implements CrvExchange {
    private final Map<String, TransactionHandler> transactions = new LinkedHashMap<>();
    private final Map<String, SignalHandler> signals = new LinkedHashMap<>();

    public CrvScriptedExchange onTransaction(
            CrvTrace.Layer layer, String channel, TransactionHandler handler) {
        transactions.put(key(layer, channel), handler);
        return this;
    }

    public CrvScriptedExchange onSignal(
            CrvTrace.Layer layer, String channel, SignalHandler handler) {
        signals.put(key(layer, channel), handler);
        return this;
    }

    @Override public byte[] transact(
            CrvTrace.Layer layer, String channel, byte[] request) throws IOException {
        TransactionHandler handler = transactions.get(key(layer, channel));
        if (handler == null) {
            throw new IOException("no scripted transaction for " + layer + '/' + channel);
        }
        return handler.handle(request).clone();
    }

    @Override public Map<String, String> awaitSignal(
            CrvTrace.Layer layer, String channel) throws IOException {
        SignalHandler handler = signals.get(key(layer, channel));
        if (handler == null) throw new IOException("no scripted signal for " + layer + '/' + channel);
        return Collections.unmodifiableMap(new TreeMap<>(handler.handle()));
    }

    public static CrvScriptedExchange successfulHardware() {
        TransactionHandler ok = request -> VirtualCrvClient.ascii("OK");
        return new CrvScriptedExchange()
                .onTransaction(CrvTrace.Layer.USB, "role-switch", ok)
                .onTransaction(CrvTrace.Layer.NCM, "configure", ok)
                .onTransaction(CrvTrace.Layer.USBMUX, "connect", ok)
                .onTransaction(CrvTrace.Layer.LOCKDOWN, "start-session", ok)
                .onTransaction(CrvTrace.Layer.MFI, "authenticate", request -> VirtualCrvClient.ascii("signature"))
                .onTransaction(CrvTrace.Layer.CARPLAY_SESSION, "rtsp-setup", ok)
                .onTransaction(CrvTrace.Layer.CARPLAY_SESSION, "rtsp-record", ok);
    }

    private static String key(CrvTrace.Layer layer, String channel) {
        return layer.name() + '\n' + channel;
    }
}
