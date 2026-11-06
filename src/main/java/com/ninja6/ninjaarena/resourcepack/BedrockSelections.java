package com.ninja6.ninjaarena.resourcepack;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.UUID;
import java.util.function.Function;

/** Connection-time quality selection. No platform classes or preference persistence. */
public final class BedrockSelections {
    public enum Quality { STANDARD, DETAILED }

    public interface Negotiator {
        void remove(UUID pack);
        void offer(Quality quality);
    }

    private final UUID standard;
    private final UUID detailed;
    private final boolean detailedAvailable;
    private final Map<Object, Quality> selections = new WeakHashMap<>();
    private volatile Function<String, Quality> selector = xuid -> Quality.STANDARD;

    public BedrockSelections(UUID standard, UUID detailed, boolean detailedAvailable) {
        this.standard = Objects.requireNonNull(standard);
        this.detailed = Objects.requireNonNull(detailed);
        this.detailedAvailable = detailedAvailable;
    }

    /** #80 supplies a fast XUID lookup; called on Geyser's event thread, never with a Java UUID. */
    public void selector(Function<String, Quality> lookup) {
        selector = Objects.requireNonNull(lookup);
    }

    public synchronized Quality negotiate(Object connection, String xuid, Negotiator negotiator) {
        Objects.requireNonNull(connection);
        Quality frozen = selections.get(connection);
        if (frozen != null) {
            return frozen;
        }
        Quality selected = Quality.STANDARD;
        if (xuid != null && !xuid.isBlank()) {
            try {
                selected = selector.apply(xuid);
            } catch (RuntimeException ignored) {
                // A failed preference lookup must not prevent joining with Standard.
            }
        }
        if (selected == null || selected == Quality.DETAILED && !detailedAvailable) {
            selected = Quality.STANDARD;
        }
        negotiator.remove(standard);
        negotiator.remove(detailed);
        negotiator.offer(selected);
        selections.put(connection, selected);
        return selected;
    }

    public synchronized boolean selected(Object connection) {
        return selections.containsKey(connection);
    }

    public synchronized void clear() {
        selections.clear();
    }
}
