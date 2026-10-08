package com.shilapi.xcertplay.crvsim;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

/** Replays production milestones and classifies whether the controller must be restarted. */
public final class CrvProductionTraceReplay {
    public enum Outcome {
        ACTIVE,
        RECOVERY_REQUIRED,
        FAILED,
        INCOMPLETE,
    }

    public static final class Report {
        private final Outcome outcome;
        private final CrvTrace.Layer terminalLayer;
        private final int consumedEvents;
        private final String message;

        private Report(
                Outcome outcome,
                CrvTrace.Layer terminalLayer,
                int consumedEvents,
                String message
        ) {
            this.outcome = outcome;
            this.terminalLayer = terminalLayer;
            this.consumedEvents = consumedEvents;
            this.message = message;
        }

        public Outcome outcome() { return outcome; }
        public CrvTrace.Layer terminalLayer() { return terminalLayer; }
        public int consumedEvents() { return consumedEvents; }
        public String message() { return message; }

        @Override public String toString() {
            return "ProductionReport{outcome=" + outcome + ", terminalLayer=" + terminalLayer +
                    ", consumedEvents=" + consumedEvents + ", message=" + message + "}";
        }
    }

    public Report replay(CrvTrace trace) throws IOException {
        Set<CrvTrace.Layer> reached = EnumSet.noneOf(CrvTrace.Layer.class);
        boolean controlStarted = false;
        boolean active = false;
        CrvTrace.Layer last = null;
        int consumed = 0;

        for (CrvTrace.Event event : trace.events()) {
            consumed++;
            reached.add(event.layer());
            last = event.layer();
            if (event.layer() == CrvTrace.Layer.CARPLAY_SESSION) {
                if ("control-start".equals(event.channel())) controlStarted = true;
                if ("active".equals(event.channel()) && event.direction() == CrvTrace.Direction.SIGNAL) {
                    active = true;
                }
            }
            if (event.direction() == CrvTrace.Direction.FAULT) {
                String detail = event.attributes().getOrDefault("detail", "recorded fault");
                if (event.layer() == CrvTrace.Layer.CARPLAY_SESSION && controlStarted && !active) {
                    return new Report(
                            Outcome.RECOVERY_REQUIRED, event.layer(), consumed, detail);
                }
                return new Report(Outcome.FAILED, event.layer(), consumed, detail);
            }
        }

        if (active) return new Report(Outcome.ACTIVE, last, consumed, "active");
        EnumSet<CrvTrace.Layer> missing = EnumSet.allOf(CrvTrace.Layer.class);
        missing.removeAll(reached);
        return new Report(
                Outcome.INCOMPLETE,
                last,
                consumed,
                missing.isEmpty() ? "session did not become active" : "missing layers: " + missing);
    }
}
