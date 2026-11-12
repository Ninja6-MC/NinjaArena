package com.ninja6.ninjaarena.room;

import com.ninja6.ninjaarena.arena.Arena;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Arena slots and who holds them. A slot is a count, at most {@link #max()} at once, plus
 * the hand-built arena it is for: one arena holds one match at a time, so no two leases name
 * the same arena. A holder that asks while no slot is free, or while its arena is taken, waits
 * in a queue and can see its position.
 *
 * <p>A lease, once granted, lasts until its holder releases it. Lowering the maximum never
 * ends one: the leases above the new maximum stay, and nobody new is let in until enough
 * of them are released.
 *
 * <p>Slots can also be used by something that holds no lease, such as a match started from
 * a waiting room ({@link #setOthers}); those count against the maximum, and the arenas they
 * use are skipped. Server thread only.
 *
 * @param <K> who holds a lease, compared by {@code equals}
 */
public final class SlotLeases<K> {

    private int max;
    private final Map<K, String> leases = new LinkedHashMap<>();
    /** Waiting holders and the arena each wants, longest waiting first. */
    private final Map<K, String> queue = new LinkedHashMap<>();
    private IntSupplier othersUsing = () -> 0;
    private Predicate<String> arenaTaken = arena -> false;

    /** @param max slots in use at once; at least 1 */
    public SlotLeases(int max) {
        setLimit(max);
    }

    /**
     * Slots used outside the leases, and arenas that cannot be leased now because something
     * else uses them (a running match, a waiting room, edit mode). Both are asked each time
     * the queue is checked.
     */
    public void setOthers(IntSupplier slotsInUse, Predicate<String> arenaInUse) {
        this.othersUsing = Objects.requireNonNull(slotsInUse, "slotsInUse");
        this.arenaTaken = Objects.requireNonNull(arenaInUse, "arenaInUse");
    }

    public int max() {
        return max;
    }

    /**
     * Changes the maximum. Leases already granted all stay, even above a lower maximum; a
     * higher one lets queued holders in.
     *
     * @return the holders granted a lease by the change, in queue order
     */
    public List<K> setMax(int max) {
        setLimit(max);
        return pump();
    }

    private void setLimit(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be at least 1");
        }
        this.max = max;
    }

    /**
     * Asks for a slot on an arena. A holder that already holds a lease on another arena gives
     * it up first, and one that was queued for another arena queues again at the back.
     *
     * @return whether the holder holds the lease now
     */
    public boolean request(K holder, String arena) {
        Objects.requireNonNull(holder, "holder");
        String name = Arena.normalizeName(arena);
        if (name.equals(leases.get(holder))) {
            return true;
        }
        if (name.equals(queue.get(holder))) {
            return false;
        }
        leases.remove(holder);
        queue.remove(holder);
        queue.put(holder, name);
        pump();
        return leases.containsKey(holder);
    }

    /**
     * Gives up a holder's lease, or its place in the queue.
     *
     * @return the holders granted a lease in its place, in queue order
     */
    public List<K> release(K holder) {
        boolean changed = leases.remove(holder) != null;
        changed |= queue.remove(holder) != null;
        return changed ? pump() : List.of();
    }

    /**
     * Grants every queued holder that can go now, longest waiting first. A holder whose arena
     * is taken is passed over but keeps its place, so a later one that wants a free arena can
     * go ahead of it.
     *
     * @return the holders granted a lease, in queue order
     */
    public List<K> pump() {
        List<K> granted = new ArrayList<>();
        for (Map.Entry<K, String> waiting : new ArrayList<>(queue.entrySet())) {
            if (used() >= max) {
                break;
            }
            String arena = waiting.getValue();
            if (leases.containsValue(arena) || arenaTaken.test(arena)) {
                continue;
            }
            queue.remove(waiting.getKey());
            leases.put(waiting.getKey(), arena);
            granted.add(waiting.getKey());
        }
        return List.copyOf(granted);
    }

    /** Slots in use: the leases plus whatever uses a slot without one. */
    public int used() {
        return leases.size() + Math.max(0, othersUsing.getAsInt());
    }

    /** Whether one more slot can be used now. */
    public boolean hasFree() {
        return used() < max;
    }

    /** The arena a holder leases, if any. */
    public Optional<String> lease(K holder) {
        return Optional.ofNullable(leases.get(holder));
    }

    public boolean isLeased(String arena) {
        return leases.containsValue(Arena.normalizeName(arena));
    }

    /** The arena a holder waits for, if it is queued. */
    public Optional<String> wanted(K holder) {
        return Optional.ofNullable(queue.get(holder));
    }

    /** Whether some holder holds or waits for this arena. */
    public boolean isClaimed(String arena) {
        String name = Arena.normalizeName(arena);
        return leases.containsValue(name) || queue.containsValue(name);
    }

    /** A queued holder's place in the queue, from 1; empty if it is not queued. */
    public OptionalInt position(K holder) {
        int position = 1;
        for (K waiting : queue.keySet()) {
            if (waiting.equals(holder)) {
                return OptionalInt.of(position);
            }
            position++;
        }
        return OptionalInt.empty();
    }

    /** How many leases are held. */
    public int leased() {
        return leases.size();
    }
}
