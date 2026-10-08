package com.shilapi.xcertplay.crvsim;

import java.io.IOException;
import java.util.Map;

/** Adapter seam shared by live recording, scripted hardware and strict replay. */
public interface CrvExchange {
    byte[] transact(CrvTrace.Layer layer, String channel, byte[] request) throws IOException;

    Map<String, String> awaitSignal(CrvTrace.Layer layer, String channel) throws IOException;

    @FunctionalInterface
    interface TransactionHandler {
        byte[] handle(byte[] request) throws IOException;
    }

    @FunctionalInterface
    interface SignalHandler {
        Map<String, String> handle() throws IOException;
    }
}
