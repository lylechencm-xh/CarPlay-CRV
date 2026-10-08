package com.shilapi.xcertplay.crvsim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Minimal production-shaped client that walks the complete wired CR-V dependency chain. */
public final class VirtualCrvClient {
    public enum State {
        IDLE,
        USB_READY,
        NCM_READY,
        USBMUX_READY,
        LOCKDOWN_READY,
        MFI_READY,
        ACTIVE,
        FAILED,
    }

    public static final class Report {
        private final State state;
        private final State lastStableState;
        private final CrvTrace.Layer failedLayer;
        private final String message;

        private Report(State state, State lastStableState, CrvTrace.Layer failedLayer, String message) {
            this.state = state;
            this.lastStableState = lastStableState;
            this.failedLayer = failedLayer;
            this.message = message;
        }

        public State state() { return state; }
        public State lastStableState() { return lastStableState; }
        public CrvTrace.Layer failedLayer() { return failedLayer; }
        public String message() { return message; }
        public boolean isActive() { return state == State.ACTIVE; }

        @Override public String toString() {
            return "Report{state=" + state + ", lastStableState=" + lastStableState +
                    ", failedLayer=" + failedLayer + ", message='" + message + "'}";
        }
    }

    private final CrvExchange exchange;

    public VirtualCrvClient(CrvExchange exchange) {
        this.exchange = Objects.requireNonNull(exchange, "exchange");
    }

    public Report connect() {
        State lastStable = State.IDLE;
        CrvTrace.Layer current = CrvTrace.Layer.USB;
        try {
            expectOk(exchange.transact(CrvTrace.Layer.USB, "role-switch", new byte[] {0x51}));
            lastStable = State.USB_READY;

            current = CrvTrace.Layer.NCM;
            expectOk(exchange.transact(
                    CrvTrace.Layer.NCM, "configure", ascii("cfg=6;ctrl=3;data=4")));
            lastStable = State.NCM_READY;

            current = CrvTrace.Layer.USBMUX;
            expectOk(exchange.transact(CrvTrace.Layer.USBMUX, "connect", ascii("port=62078")));
            lastStable = State.USBMUX_READY;

            current = CrvTrace.Layer.LOCKDOWN;
            expectOk(exchange.transact(
                    CrvTrace.Layer.LOCKDOWN, "start-session", ascii("tls=1.2")));
            lastStable = State.LOCKDOWN_READY;

            current = CrvTrace.Layer.MFI;
            byte[] signature = exchange.transact(
                    CrvTrace.Layer.MFI, "authenticate", deterministicChallenge());
            if (signature.length == 0) throw new IOException("empty MFi signature");
            lastStable = State.MFI_READY;

            current = CrvTrace.Layer.CARPLAY_SESSION;
            expectOk(exchange.transact(
                    CrvTrace.Layer.CARPLAY_SESSION,
                    "rtsp-setup",
                    ascii("timingPort,eventPort,enabledFeatures")));
            expectOk(exchange.transact(
                    CrvTrace.Layer.CARPLAY_SESSION, "rtsp-record", new byte[0]));
            return new Report(State.ACTIVE, State.ACTIVE, null, "active");
        } catch (IOException | RuntimeException error) {
            return new Report(
                    State.FAILED,
                    lastStable,
                    current,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private static void expectOk(byte[] response) throws IOException {
        if (!Arrays.equals(response, ascii("OK"))) {
            throw new IOException(
                    "expected OK, received " + Base64.getEncoder().encodeToString(response));
        }
    }

    static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] deterministicChallenge() {
        byte[] challenge = new byte[32];
        for (int index = 0; index < challenge.length; index++) challenge[index] = (byte) index;
        return challenge;
    }
}
