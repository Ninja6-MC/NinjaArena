package com.ninja6.ninjaarena.map;

import com.ninja6.ninjaarena.map.SeedSettings.Mode;
import com.ninja6.ninjaarena.map.SeedSettings.Sharing;
import com.ninja6.ninjaarena.map.SeedSettings.SlotSettings;
import com.ninja6.ninjaarena.room.SeedChoice;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Picks the seed and difficulty of a slot's next generated map from {@link SeedSettings}.
 *
 * <p><b>When.</b> A slot asks with {@link #next} each time its map is generated: before each
 * match, and in each shop break with {@code regenerate: round}. Nothing here changes a map
 * already placed: a rotation, a reroll, a timer that runs out and a {@link Request} all take
 * effect at the slot's next generation, never during a match.
 *
 * <p><b>Modes.</b> {@code static} gives {@code seed}. {@code rotation} goes through
 * {@code seeds} in order; the first time from the slot's own place in the list (slot 1 from
 * the first seed, slot 2 from the second, and so on, under {@code distinct}), moving on once
 * {@code rotate-every} has passed since it last moved, or at every generation for 0.
 * {@code random} picks a seed and keeps it until {@code reroll-every} has passed, or picks
 * again at every generation for 0.
 *
 * <p><b>Sharing.</b> Under {@link Sharing#DISTINCT} no seed is given to a slot while another
 * active slot plays it, per-round regeneration included. The caller says which seeds are
 * active elsewhere. A static seed set on several slots is kept by the first and the later
 * ones fall back to random seeds ({@link #warnings}); a rotation whose free seeds run out
 * gives the extra slots random seeds, which is logged; a random seed is never one in use.
 * Under {@link Sharing#SHARED} every slot follows {@code maps.default} and one state: the
 * slots' overrides are ignored, with a warning. With a timer of 0 the shared seed moves on
 * only at a generation while no other active slot plays it, so slots active together play
 * one seed; with a timer, once it has run out, at the next generation.
 *
 * <p>The rotation place and the random seed in force are held in memory only, so a restart
 * begins each rotation again and picks new random seeds. The clock and the random numbers
 * are given, for tests. Server thread only.
 */
public final class SeedPolicy {

    /** What a room or an admin asked for its next generation. */
    public enum Request {
        /** Nothing: the mode and its timers decide. */
        NONE,
        /** The next seed of the rotation; a new random seed for a random slot. */
        NEXT,
        /**
         * A new random seed, whatever the mode; a random slot keeps it as its seed. Under
         * {@code shared}, where a one-off seed would leave the other slots, a rotation moves
         * on as for {@link #NEXT} and a static seed stays.
         */
        REROLL
    }

    /** Where a seed came from. */
    public enum Source { STATIC, ROTATION, RANDOM }

    /** A seed for a slot's next map, and where it came from. */
    public record Pick(SeedChoice choice, Source source) {

        public long seed() {
            return choice.seed();
        }
    }

    private static final class State {
        List<Long> rotation;
        int index = -1;
        long movedAt;
        OptionalLong current = OptionalLong.empty();
        long rolledAt;
        Request requested = Request.NONE;
    }

    private SeedSettings settings;
    private final LongSupplier clock;
    private final LongSupplier random;
    private final Consumer<String> log;
    private final Map<Integer, State> states = new HashMap<>();

    /**
     * @param clock  the time now, in milliseconds
     * @param random a random seed for each call
     * @param log    told each time a slot falls back to a random seed, as a server log line
     */
    public SeedPolicy(SeedSettings settings, LongSupplier clock, LongSupplier random, Consumer<String> log) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.log = Objects.requireNonNull(log, "log");
    }

    public SeedSettings settings() {
        return settings;
    }

    /**
     * New settings on reload, for each slot's next generation. A rotation keeps its place
     * while its seed list is the same, and a random seed is kept while the slot is still
     * random; both start again when the sharing rule changes.
     */
    public void setSettings(SeedSettings next) {
        Objects.requireNonNull(next, "next");
        if (next.sharing() != settings.sharing()) {
            states.clear();
        }
        settings = next;
    }

    /** The settings slot {@code n} uses now. */
    public SlotSettings forSlot(int slot) {
        return settings.forSlot(slot);
    }

    /**
     * What is worth a warning for slots 1 to {@code slots}, as server log lines: a static
     * seed set on several slots under {@code distinct}, whose later slots fall back to
     * random seeds, and slot overrides under {@code shared}, which are ignored. Empty for a
     * default install.
     */
    public List<String> warnings(int slots) {
        List<String> warnings = new ArrayList<>();
        if (settings.sharing() == Sharing.SHARED) {
            if (!settings.slots().isEmpty()) {
                warnings.add("maps.slots sets " + settings.slots().keySet().stream().map(String::valueOf)
                        .reduce((a, b) -> a + ", " + b).orElse("") + ", but maps.seed-sharing is shared, so every"
                        + " slot follows maps.default and the overrides are ignored.");
            }
            return warnings;
        }
        Map<Long, List<Integer>> statics = new LinkedHashMap<>();
        for (int slot = 1; slot <= slots; slot++) {
            SlotSettings own = settings.forSlot(slot);
            if (own.mode() == Mode.STATIC) {
                statics.computeIfAbsent(own.seed().orElseThrow(), seed -> new ArrayList<>()).add(slot);
            }
        }
        statics.forEach((seed, users) -> {
            if (users.size() > 1) {
                warnings.add("Slots " + list(users) + " all use static seed " + seed + ", but maps.seed-sharing is"
                        + " distinct, so only slot " + users.getFirst() + " plays it and slot"
                        + (users.size() > 2 ? "s " : " ") + list(users.subList(1, users.size()))
                        + " fall" + (users.size() > 2 ? "" : "s") + " back to random seeds.");
            }
        });
        return warnings;
    }

    private static String list(List<Integer> slots) {
        if (slots.size() == 1) {
            return String.valueOf(slots.getFirst());
        }
        List<String> names = slots.stream().map(String::valueOf).toList();
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    /**
     * Asks for the next rotation seed, or a new random seed, at the slot's next generation.
     * Under {@code shared} it is asked for every slot.
     */
    public void request(int slot, Request request) {
        Objects.requireNonNull(request, "request");
        if (request != Request.NONE) {
            state(slot).requested = request;
        }
    }

    /** Whether the seed may not be given to a slot: under {@code distinct}, it is active elsewhere. */
    public boolean collides(long seed, Set<Long> activeElsewhere) {
        return settings.sharing() == Sharing.DISTINCT && activeElsewhere.contains(seed);
    }

    /**
     * The seed and difficulty of the slot's next map, now that it is generated. The
     * request, and any made with {@link #request}, is used up.
     *
     * @param activeElsewhere the seeds other slots play or are generating now; under
     *                        {@code shared} they never refuse a seed, and only keep the
     *                        shared seed in place while another slot plays it
     */
    public Pick next(int slot, Set<Long> activeElsewhere, Request request) {
        Objects.requireNonNull(activeElsewhere, "activeElsewhere");
        Objects.requireNonNull(request, "request");
        Set<Long> taken = settings.sharing() == Sharing.DISTINCT ? activeElsewhere : Set.of();
        SlotSettings own = settings.forSlot(slot);
        State state = state(slot);
        Request asked = request != Request.NONE ? request : state.requested;
        state.requested = Request.NONE;
        long now = clock.getAsLong();
        Mode mode = mode(slot, own);
        if (asked == Request.REROLL && mode != Mode.RANDOM) {
            if (settings.sharing() == Sharing.SHARED) {
                // A one-off seed would leave the other slots; under shared it moves the rotation on for all.
                asked = mode == Mode.ROTATION ? Request.NEXT : Request.NONE;
            } else {
                return pick(own, randomSeed(taken), Source.RANDOM);
            }
        }
        return switch (mode) {
            case STATIC -> {
                long seed = own.seed().orElseThrow();
                if (taken.contains(seed)) {
                    long instead = randomSeed(taken);
                    log.accept("Slot " + slot + " uses static seed " + seed + ", which another slot plays now, so it"
                            + " gets random seed " + instead + " this time.");
                    yield pick(own, instead, Source.RANDOM);
                }
                yield pick(own, seed, Source.STATIC);
            }
            case ROTATION -> rotation(slot, own, state, taken, activeElsewhere, now, asked == Request.NEXT);
            case RANDOM -> {
                if (state.current.isEmpty() || asked != Request.NONE
                        || moves(own.rerollEvery(), state.rolledAt, now, state.current, activeElsewhere)
                        || taken.contains(state.current.getAsLong())) {
                    state.current = OptionalLong.of(randomSeed(taken));
                    state.rolledAt = now;
                }
                yield pick(own, state.current.getAsLong(), Source.RANDOM);
            }
        };
    }

    /**
     * What {@link #next} would give the slot now, without moving anything on, when that is
     * known: a static seed, the rotation seed, or the random seed in force. Empty when a new
     * random seed is due. For showing the next map before it is generated.
     */
    public Optional<SeedChoice> preview(int slot, Set<Long> activeElsewhere) {
        Set<Long> taken = settings.sharing() == Sharing.DISTINCT ? activeElsewhere : Set.of();
        SlotSettings own = settings.forSlot(slot);
        State state = states.get(key(slot));
        Request asked = state == null ? Request.NONE : state.requested;
        long now = clock.getAsLong();
        Mode mode = mode(slot, own);
        if (asked == Request.REROLL && settings.sharing() == Sharing.SHARED && mode != Mode.RANDOM) {
            asked = mode == Mode.ROTATION ? Request.NEXT : Request.NONE;
        } else if (asked == Request.REROLL) {
            return Optional.empty();
        }
        return switch (mode) {
            case STATIC -> taken.contains(own.seed().orElseThrow()) ? Optional.empty()
                    : Optional.of(new SeedChoice(own.seed().orElseThrow(), own.difficulty()));
            case ROTATION -> {
                List<Long> seeds = own.seeds();
                int index = state == null || state.index < 0 || !seeds.equals(state.rotation) ? start(slot, seeds)
                        : asked == Request.NEXT || moves(own.rotateEvery(), state.movedAt, now,
                                OptionalLong.of(seeds.get(state.index)), activeElsewhere)
                                ? (state.index + 1) % seeds.size() : state.index;
                yield free(seeds, index, taken).map(seed -> new SeedChoice(seed, own.difficulty()));
            }
            case RANDOM -> state == null || state.current.isEmpty() || asked != Request.NONE
                    || moves(own.rerollEvery(), state.rolledAt, now, state.current, activeElsewhere)
                    || taken.contains(state.current.getAsLong())
                    ? Optional.empty() : Optional.of(new SeedChoice(state.current.getAsLong(), own.difficulty()));
        };
    }

    private Pick rotation(int slot, SlotSettings own, State state, Set<Long> taken, Set<Long> elsewhere, long now,
            boolean next) {
        List<Long> seeds = own.seeds();
        if (state.index < 0 || !seeds.equals(state.rotation)) {
            state.rotation = seeds;
            state.index = start(slot, seeds);
            state.movedAt = now;
        } else if (next || moves(own.rotateEvery(), state.movedAt, now, OptionalLong.of(seeds.get(state.index)),
                elsewhere)) {
            state.index = (state.index + 1) % seeds.size();
            state.movedAt = now;
        }
        Optional<Long> seed = free(seeds, state.index, taken);
        if (seed.isPresent()) {
            return pick(own, seed.get(), Source.ROTATION);
        }
        long instead = randomSeed(taken);
        log.accept("Slot " + slot + " rotates through " + seeds.size() + " seed(s), but other active slots play all"
                + " of them, so it gets random seed " + instead + " this time.");
        return pick(own, instead, Source.RANDOM);
    }

    /** The first seed from the index on, wrapping round, that is not taken. */
    private static Optional<Long> free(List<Long> seeds, int index, Set<Long> taken) {
        for (int i = 0; i < seeds.size(); i++) {
            long seed = seeds.get((index + i) % seeds.size());
            if (!taken.contains(seed)) {
                return Optional.of(seed);
            }
        }
        return Optional.empty();
    }

    /** Where a rotation starts: slot n at its own place under distinct, at the start when shared. */
    private int start(int slot, List<Long> seeds) {
        return settings.sharing() == Sharing.SHARED ? 0 : Math.floorMod(slot - 1, seeds.size());
    }

    /**
     * The mode the slot plays under: its own, except that under {@code distinct} a static seed
     * an earlier slot has too falls back to random.
     */
    private Mode mode(int slot, SlotSettings own) {
        if (own.mode() != Mode.STATIC || settings.sharing() != Sharing.DISTINCT) {
            return own.mode();
        }
        for (int earlier = 1; earlier < slot; earlier++) {
            SlotSettings other = settings.forSlot(earlier);
            if (other.mode() == Mode.STATIC && other.seed().equals(own.seed())) {
                return Mode.RANDOM;
            }
        }
        return Mode.STATIC;
    }

    /**
     * Whether a rotation or a random seed moves on at this generation. With a timer, once it
     * has run out. With 0 under {@code distinct}, at every generation. With 0 under
     * {@code shared}, only when no other active slot still plays the current seed: slots
     * generating while one of them plays it all get it, so they stay on one seed, and a slot
     * on its own gets a new one each time.
     */
    private boolean moves(Duration every, long since, long now, OptionalLong current, Set<Long> elsewhere) {
        if (every.isZero() && settings.sharing() == Sharing.SHARED) {
            return current.isEmpty() || !elsewhere.contains(current.getAsLong());
        }
        return due(every, since, now);
    }

    private static boolean due(Duration every, long since, long now) {
        return every.isZero() || now - since >= every.toMillis();
    }

    private long randomSeed(Set<Long> taken) {
        long seed = random.getAsLong();
        while (taken.contains(seed)) {
            seed = random.getAsLong();
        }
        return seed;
    }

    private static Pick pick(SlotSettings own, long seed, Source source) {
        return new Pick(new SeedChoice(seed, own.difficulty()), source);
    }

    private State state(int slot) {
        return states.computeIfAbsent(key(slot), key -> new State());
    }

    /** Under shared, every slot follows one state. */
    private int key(int slot) {
        return settings.sharing() == Sharing.SHARED ? 0 : slot;
    }
}
