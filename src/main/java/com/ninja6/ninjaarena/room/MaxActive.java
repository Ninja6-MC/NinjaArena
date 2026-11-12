package com.ninja6.ninjaarena.room;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * {@code arenas.max-active}: how many arena slots are used at once. It replaced
 * {@code stability.max-concurrent-matches}, which is still read, with a warning, when the
 * new name is not set.
 */
public final class MaxActive {

    public static final String KEY = "arenas.max-active";
    /** The name before rooms, read when {@link #KEY} is not set. */
    public static final String OLD_KEY = "stability.max-concurrent-matches";
    public static final int DEFAULT = 4;
    public static final int MIN = 1;
    public static final int MAX = 100;

    private MaxActive() { }

    /**
     * Reads the value set in {@code config.yml}, ignoring bundled defaults, clamped to
     * {@link #MIN} to {@link #MAX}.
     *
     * @param warnings told when the old name is used or ignored, as a server log line
     */
    public static int read(ConfigurationSection config, Consumer<String> warnings) {
        boolean current = config.contains(KEY, true);
        boolean old = config.contains(OLD_KEY, true);
        if (old) {
            warnings.accept(warning(current));
        }
        int value = current ? config.getInt(KEY, DEFAULT) : old ? config.getInt(OLD_KEY, DEFAULT) : DEFAULT;
        return Math.clamp(value, MIN, MAX);
    }

    /** The server log line for a config that still names {@link #OLD_KEY}. */
    public static String warning(boolean currentSet) {
        return currentSet
                ? OLD_KEY + " is the old name of " + KEY + ", which is also set, so it is ignored. Remove it."
                : OLD_KEY + " has been renamed " + KEY + ". The old name still works for now; rename it in config.yml.";
    }
}
