package com.ninja6.ninjaarena.map;

import com.ninja6.ninjaarena.room.MaxActive;
import com.ninja6.ninjaarena.terrain.TerrainGenerator;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code maps} section of {@code config.yml}: how each slot cell picks the seed and the
 * difficulty of its next generated map. {@code maps.default} applies to every slot,
 * {@code maps.slots.<n>} overrides any of its keys for slot {@code n}, and
 * {@code maps.seed-sharing} says whether slots may play the same seed at once.
 *
 * @param defaults the {@code maps.default} settings
 * @param slots    each slot with overrides, with {@code defaults} filled in for the keys it
 *                 does not set
 * @param sharing  {@code maps.seed-sharing}
 */
public record SeedSettings(SlotSettings defaults, Map<Integer, SlotSettings> slots, Sharing sharing) {

    /** How a slot picks its seed. */
    public enum Mode {
        /** Always {@code seed}. */
        STATIC,
        /** The {@code seeds} list in order, moving on after {@code rotate-every}. */
        ROTATION,
        /** A random seed, picked again after {@code reroll-every}. */
        RANDOM
    }

    /** When a slot's map is generated again. */
    public enum Regenerate {
        /** Before each match. */
        MATCH,
        /** Before each match and in each shop break between rounds. */
        ROUND
    }

    /** {@code maps.seed-sharing}. */
    public enum Sharing {
        /** No two active slots play the same seed at once. */
        DISTINCT,
        /** Every slot follows the same seed, from {@code maps.default}. */
        SHARED
    }

    /** The lowest difficulty a slot can use, the generator's. */
    public static final int MIN_DIFFICULTY = TerrainGenerator.MIN_DIFFICULTY;
    /** The highest difficulty a slot can use, the generator's. */
    public static final int MAX_DIFFICULTY = TerrainGenerator.MAX_DIFFICULTY;
    /** The difficulty of {@code maps.default} when it sets none. */
    public static final int DEFAULT_DIFFICULTY = 2;

    /**
     * One slot's settings.
     *
     * @param seed        {@code seed}; empty when none is set
     * @param seeds       {@code seeds}, for rotation
     * @param rotateEvery {@code rotate-every}; zero moves on at every generation
     * @param rerollEvery {@code reroll-every}; zero picks again at every generation
     */
    public record SlotSettings(Mode mode, OptionalLong seed, List<Long> seeds, Duration rotateEvery,
            Duration rerollEvery, int difficulty, Regenerate regenerate) {

        public SlotSettings {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(seed, "seed");
            seeds = List.copyOf(seeds);
            Objects.requireNonNull(rotateEvery, "rotateEvery");
            Objects.requireNonNull(rerollEvery, "rerollEvery");
            Objects.requireNonNull(regenerate, "regenerate");
            if (difficulty < MIN_DIFFICULTY || difficulty > MAX_DIFFICULTY) {
                throw new IllegalArgumentException("difficulty must be " + MIN_DIFFICULTY + " to " + MAX_DIFFICULTY);
            }
        }
    }

    /** A setting that cannot be read, with the path of the key it is under. */
    public static final class Invalid extends IllegalArgumentException {

        private final String path;
        private final String reason;

        Invalid(String path, String reason) {
            super(path + ": " + reason);
            this.path = path;
            this.reason = reason;
        }

        public String path() {
            return path;
        }

        public String reason() {
            return reason;
        }
    }

    /** The section of {@code config.yml} these settings are read from. */
    public static final String SECTION = "maps";

    /** The keys {@code maps.default} and each {@code maps.slots.<n>} can set. */
    public static final List<String> KEYS = List.of("mode", "seed", "seeds", "rotate-every", "reroll-every",
            "difficulty", "regenerate");

    /** A default install: random seeds at difficulty 2, a map for each match, distinct seeds. */
    public static final SeedSettings DEFAULTS = new SeedSettings(new SlotSettings(Mode.RANDOM, OptionalLong.empty(),
            List.of(), Duration.ZERO, Duration.ZERO, DEFAULT_DIFFICULTY, Regenerate.MATCH), Map.of(),
            Sharing.DISTINCT);

    public SeedSettings {
        Objects.requireNonNull(defaults, "defaults");
        slots = Collections.unmodifiableMap(new TreeMap<>(slots));
        Objects.requireNonNull(sharing, "sharing");
    }

    /**
     * The settings slot {@code n} uses: its overrides on top of {@code maps.default}, or
     * {@code maps.default} alone under {@link Sharing#SHARED}, which ignores the overrides.
     */
    public SlotSettings forSlot(int slot) {
        return sharing == Sharing.SHARED ? defaults : slots.getOrDefault(slot, defaults);
    }

    /** Whether any of slots 1 to {@code count} generates its map again between rounds. */
    public boolean regeneratesRounds(int count) {
        for (int slot = 1; slot <= count; slot++) {
            if (forSlot(slot).regenerate() == Regenerate.ROUND) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the {@code maps} section; a missing section or key takes its default.
     *
     * @throws Invalid naming the first key that cannot be read
     */
    public static SeedSettings from(ConfigurationSection maps) {
        if (maps == null) {
            return DEFAULTS;
        }
        for (String key : maps.getKeys(false)) {
            if (!Set.of("default", "slots", "seed-sharing").contains(key)) {
                throw new Invalid("maps." + key, "unknown setting");
            }
        }
        Sharing sharing = choice(maps.get("seed-sharing", "distinct"), "maps.seed-sharing", Sharing.class,
                "distinct or shared");
        SlotSettings defaults = slot(section(maps, "default", "maps.default"), DEFAULTS.defaults(), "maps.default");
        Map<Integer, SlotSettings> slots = new TreeMap<>();
        ConfigurationSection overrides = section(maps, "slots", "maps.slots");
        if (overrides != null) {
            for (String key : overrides.getKeys(false)) {
                String path = "maps.slots." + key;
                int number;
                try {
                    number = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    throw new Invalid(path, "expected a slot number from 1 to " + MaxActive.MAX);
                }
                if (number < 1 || number > MaxActive.MAX) {
                    throw new Invalid(path, "expected a slot number from 1 to " + MaxActive.MAX);
                }
                ConfigurationSection slot = section(overrides, key, path);
                slots.put(number, slot(slot, defaults, path));
            }
        }
        return new SeedSettings(defaults, slots, sharing);
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key, String path) {
        if (!parent.contains(key)) {
            return null;
        }
        if (!parent.isConfigurationSection(key)) {
            throw new Invalid(path, "expected a section");
        }
        return parent.getConfigurationSection(key);
    }

    private static SlotSettings slot(ConfigurationSection section, SlotSettings base, String path) {
        if (section == null) {
            return base;
        }
        for (String key : section.getKeys(false)) {
            if (!KEYS.contains(key)) {
                throw new Invalid(path + "." + key, "unknown setting");
            }
        }
        Mode mode = section.contains("mode")
                ? choice(section.get("mode"), path + ".mode", Mode.class, "static, rotation or random") : base.mode();
        OptionalLong seed = section.contains("seed") ? seed(section.get("seed"), path + ".seed") : base.seed();
        List<Long> seeds = section.contains("seeds") ? seeds(section.get("seeds"), path + ".seeds") : base.seeds();
        Duration rotate = section.contains("rotate-every")
                ? duration(section.get("rotate-every"), path + ".rotate-every") : base.rotateEvery();
        Duration reroll = section.contains("reroll-every")
                ? duration(section.get("reroll-every"), path + ".reroll-every") : base.rerollEvery();
        int difficulty = section.contains("difficulty")
                ? difficulty(section.get("difficulty"), path + ".difficulty") : base.difficulty();
        Regenerate regenerate = section.contains("regenerate")
                ? choice(section.get("regenerate"), path + ".regenerate", Regenerate.class, "match or round")
                : base.regenerate();
        if (mode == Mode.STATIC && seed.isEmpty()) {
            throw new Invalid(path + (section.contains("seed") ? ".seed" : ".mode"), "static mode needs a seed");
        }
        if (mode == Mode.ROTATION && seeds.isEmpty()) {
            throw new Invalid(path + (section.contains("seeds") ? ".seeds" : ".mode"),
                    "rotation mode needs at least one seed in seeds");
        }
        return new SlotSettings(mode, seed, seeds, rotate, reroll, difficulty, regenerate);
    }

    private static <E extends Enum<E>> E choice(Object value, String path, Class<E> type, String expected) {
        if (value instanceof String text) {
            for (E option : type.getEnumConstants()) {
                if (option.name().toLowerCase(Locale.ROOT).equals(text.trim().toLowerCase(Locale.ROOT))) {
                    return option;
                }
            }
        }
        throw new Invalid(path, "expected " + expected);
    }

    /** A seed: a whole number, also as text; empty text for none. */
    static OptionalLong seed(Object value, String path) {
        if (value instanceof String text) {
            if (text.isBlank()) {
                return OptionalLong.empty();
            }
            try {
                return OptionalLong.of(Long.parseLong(text.trim()));
            } catch (NumberFormatException e) {
                throw new Invalid(path, "expected a whole number from " + Long.MIN_VALUE + " to " + Long.MAX_VALUE);
            }
        }
        return OptionalLong.of(whole(value, path));
    }

    private static long whole(Object value, String path) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger big && big.bitLength() < Long.SIZE) {
            return big.longValue();
        }
        throw new Invalid(path, "expected a whole number from " + Long.MIN_VALUE + " to " + Long.MAX_VALUE);
    }

    private static List<Long> seeds(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new Invalid(path, "expected a list of seeds");
        }
        List<Long> seeds = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String at = path + "." + i;
            long seed = item instanceof String text
                    ? seed(text, at).orElseThrow(() -> new Invalid(at, "expected a seed")) : whole(item, at);
            if (seeds.contains(seed)) {
                throw new Invalid(at, "seed " + seed + " is in the list twice");
            }
            seeds.add(seed);
        }
        return seeds;
    }

    private static Duration duration(Object value, String path) {
        return Durations.parse(value).orElseThrow(() -> new Invalid(path,
                "expected a duration such as 30m, 2h or 1h30m (units d, h, m, s, at most "
                        + Durations.format(Durations.MAX) + "), or 0"));
    }

    private static int difficulty(Object value, String path) {
        if (value instanceof Integer number && number >= MIN_DIFFICULTY && number <= MAX_DIFFICULTY) {
            return number;
        }
        throw new Invalid(path, "expected a whole number from " + MIN_DIFFICULTY + " to " + MAX_DIFFICULTY);
    }
}
