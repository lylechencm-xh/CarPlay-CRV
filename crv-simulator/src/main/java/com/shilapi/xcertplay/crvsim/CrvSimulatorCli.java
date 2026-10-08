package com.shilapi.xcertplay.crvsim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/** Small CLI for CI and local fixture inspection. */
public final class CrvSimulatorCli {
    private CrvSimulatorCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        switch (args[0]) {
            case "record-demo":
                requireLength(args, 2);
                recordDemo(Paths.get(args[1]));
                return;
            case "replay":
                requireLength(args, 2);
                replay(Paths.get(args[1]));
                return;
            case "import-log":
                requireLength(args, 3);
                importLog(Paths.get(args[1]), Paths.get(args[2]));
                return;
            case "replay-production":
                requireLength(args, 2);
                replayProduction(Paths.get(args[1]));
                return;
            default:
                usage();
                System.exit(2);
        }
    }

    private static void recordDemo(Path tracePath) throws IOException {
        AtomicLong nanos = new AtomicLong();
        CrvTrace.Recorder recorder = new CrvTrace.Recorder(() -> nanos.addAndGet(1_000_000L));
        CrvRecordingExchange recording = new CrvRecordingExchange(
                CrvScriptedExchange.successfulHardware(), recorder);
        VirtualCrvClient.Report report = new VirtualCrvClient(recording).connect();
        if (!report.isActive()) throw new IOException("demo recording failed: " + report);
        CrvTrace trace = recorder.snapshot();
        trace.requireFullCrvCoverage();
        Path parent = tracePath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        CrvTraceCodec.write(tracePath, trace);
        System.out.println("recorded " + trace.events().size() + " events to " + tracePath);
    }

    private static void replay(Path tracePath) throws IOException {
        CrvTrace trace = CrvTraceCodec.read(tracePath);
        trace.requireFullCrvCoverage();
        CrvReplayExchange replay = new CrvReplayExchange(trace);
        VirtualCrvClient.Report report = new VirtualCrvClient(replay).connect();
        System.out.println(report);
        if (!report.isActive() || !replay.isExhausted()) {
            throw new IOException(
                    "replay failed or left " + replay.remainingEvents() + " unconsumed events");
        }
    }

    private static void importLog(Path logPath, Path tracePath) throws IOException {
        CrvTrace trace = CrvProductionLogImporter.read(logPath);
        trace.requireFullCrvCoverage();
        Path parent = tracePath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        CrvTraceCodec.write(tracePath, trace);
        System.out.println("imported " + trace.events().size() + " production events to " + tracePath);
    }

    private static void replayProduction(Path tracePath) throws IOException {
        CrvTrace trace = CrvTraceCodec.read(tracePath);
        CrvProductionTraceReplay.Report report = new CrvProductionTraceReplay().replay(trace);
        System.out.println(report);
        if (report.outcome() == CrvProductionTraceReplay.Outcome.INCOMPLETE) {
            throw new IOException("production replay incomplete: " + report.message());
        }
    }

    private static void requireLength(String[] args, int expected) {
        if (args.length != expected) {
            usage();
            throw new IllegalArgumentException("invalid argument count");
        }
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  crv-simulator record-demo <trace.crvtrace>");
        System.err.println("  crv-simulator replay <trace.crvtrace>");
        System.err.println("  crv-simulator import-log <car.log> <trace.crvtrace>");
        System.err.println("  crv-simulator replay-production <trace.crvtrace>");
    }
}
