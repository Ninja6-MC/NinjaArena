package com.ninja6.ninjaarena.world;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Tunable values for the arena world, read from the {@code arena-world} section of
 * {@code config.yml}. Out-of-range values are clamped to what the server accepts.
 *
 * @param borderSize         world border diameter in blocks, centred on 0, 0
 * @param viewDistance       chunk radius sent to players in the arena world
 * @param simulationDistance chunk radius around players that ticks entities and blocks
 */
public record ArenaWorldSettings(double borderSize, int viewDistance, int simulationDistance) {

    public static final double DEFAULT_BORDER_SIZE = 2048;
    public static final int DEFAULT_VIEW_DISTANCE = 6;
    public static final int DEFAULT_SIMULATION_DISTANCE = 4;

    static final double MIN_BORDER_SIZE = 16;
    /** A finite gameplay footprint bounds partial holders even without an idle interval. */
    public static final double MAX_BORDER_SIZE = 2048;
    /** Range {@code World#setViewDistance} and {@code World#setSimulationDistance} accept. */
    static final int MIN_DISTANCE = 2;
    static final int MAX_DISTANCE = 32;

    public static final ArenaWorldSettings DEFAULTS =
            new ArenaWorldSettings(DEFAULT_BORDER_SIZE, DEFAULT_VIEW_DISTANCE, DEFAULT_SIMULATION_DISTANCE);

    public ArenaWorldSettings {
        if (!Double.isFinite(borderSize)) {
            borderSize = DEFAULT_BORDER_SIZE;
        }
        borderSize = Math.clamp(borderSize, MIN_BORDER_SIZE, MAX_BORDER_SIZE);
        viewDistance = Math.clamp(viewDistance, MIN_DISTANCE, MAX_DISTANCE);
        simulationDistance = Math.clamp(simulationDistance, MIN_DISTANCE, MAX_DISTANCE);
    }

    /**
     * Reads the settings from a config section, falling back to the defaults for a missing
     * section or key.
     */
    public static ArenaWorldSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        return new ArenaWorldSettings(
                section.getDouble("border-size", DEFAULT_BORDER_SIZE),
                section.getInt("view-distance", DEFAULT_VIEW_DISTANCE),
                section.getInt("simulation-distance", DEFAULT_SIMULATION_DISTANCE));
    }
}
