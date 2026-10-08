package com.shilapi.xcertplay.crvsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable, versioned timeline spanning every CR-V connection layer. */
public final class CrvTrace {
    public static final int VERSION = 1;
    public static final String HEADER = "CRVTRACE\t" + VERSION;

    public enum Layer {
        USB,
        NCM,
        USBMUX,
        LOCKDOWN,
        MFI,
        CARPLAY_SESSION,
    }

    public enum Direction {
        HOST_TO_DEVICE,
        DEVICE_TO_HOST,
        SIGNAL,
        FAULT,
    }

    public interface NanoClock {
        long nanoTime();
    }

    public static final class Event {
        private final long index;
        private final long offsetMicros;
        private final Layer layer;
        private final Direction direction;
        private final String channel;
        private final byte[] payload;
        private final Map<String, String> attributes;

        public Event(
                long index,
                long offsetMicros,
                Layer layer,
                Direction direction,
                String channel,
                byte[] payload,
                Map<String, String> attributes
        ) {
            if (index < 0) throw new IllegalArgumentException("index must be non-negative");
            if (offsetMicros < 0) throw new IllegalArgumentException("offsetMicros must be non-negative");
            this.index = index;
            this.offsetMicros = offsetMicros;
            this.layer = Objects.requireNonNull(layer, "layer");
            this.direction = Objects.requireNonNull(direction, "direction");
            this.channel = Objects.requireNonNull(channel, "channel");
            this.payload = Objects.requireNonNull(payload, "payload").clone();
            this.attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
        }

        public long index() { return index; }
        public long offsetMicros() { return offsetMicros; }
        public Layer layer() { return layer; }
        public Direction direction() { return direction; }
        public String channel() { return channel; }
        public byte[] payload() { return payload.clone(); }
        public Map<String, String> attributes() { return attributes; }

        @Override public String toString() {
            return "Event{" + index + ", " + layer + ", " + direction + ", " + channel +
                    ", bytes=" + payload.length + '}';
        }
    }

    public static final class Recorder {
        private final NanoClock clock;
        private final long startedNanos;
        private final AtomicLong nextIndex = new AtomicLong();
        private final List<Event> events = new ArrayList<>();

        public Recorder() { this(System::nanoTime); }

        public Recorder(NanoClock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            this.startedNanos = clock.nanoTime();
        }

        public synchronized void record(
                Layer layer,
                Direction direction,
                String channel,
                byte[] payload,
                Map<String, String> attributes
        ) {
            long offset = Math.max(0, (clock.nanoTime() - startedNanos) / 1_000L);
            events.add(new Event(
                    nextIndex.getAndIncrement(), offset, layer, direction, channel, payload, attributes));
        }

        public synchronized CrvTrace snapshot() { return new CrvTrace(new ArrayList<>(events)); }
    }

    private final List<Event> events;

    public CrvTrace(List<Event> events) {
        long previousIndex = -1;
        long previousOffset = -1;
        List<Event> copy = new ArrayList<>(events.size());
        for (Event event : events) {
            if (event.index() <= previousIndex) {
                throw new IllegalArgumentException("event indices must be strictly increasing");
            }
            if (event.offsetMicros() < previousOffset) {
                throw new IllegalArgumentException("event offsets must be monotonic");
            }
            previousIndex = event.index();
            previousOffset = event.offsetMicros();
            copy.add(event);
        }
        this.events = Collections.unmodifiableList(copy);
    }

    public List<Event> events() { return events; }

    public Set<Layer> coveredLayers() {
        EnumSet<Layer> layers = EnumSet.noneOf(Layer.class);
        for (Event event : events) layers.add(event.layer());
        return Collections.unmodifiableSet(layers);
    }

    public void requireFullCrvCoverage() {
        EnumSet<Layer> missing = EnumSet.allOf(Layer.class);
        missing.removeAll(coveredLayers());
        if (!missing.isEmpty()) throw new IllegalStateException("trace is missing layers: " + missing);
    }
}
