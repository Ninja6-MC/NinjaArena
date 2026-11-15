package com.ninja6.ninjaarena.hazard;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Limits on flowing lava in generated maps and the timing of late hazards, read from the
 * {@code stability.hazards} section of {@code config.yml}. Out-of-range values are clamped.
 *
 * <p>The caps come from measuring real lava flow on Paper 26.3: changing many sources in one
 * tick spikes the tick time, while flowing lava costs little once it runs. So no more than
 * {@link #MAX_SOURCES_PER_MAP} sources flow in one map and {@link #MAX_SOURCES} across every
 * map at once, and sources start and stop a few at a time
 * ({@code CellWriter#MAX_CHANGES_PER_TICK}). Late hazards count against the same caps.
 *
 * @param maxSourcesPerMap    lava sources flowing at once in one map, 0 for none
 * @param maxSources          lava sources flowing at once across every map, 0 for none
 * @param flowSeconds         how long a source flows before it is taken away, if the round
 *                            has not ended first
 * @param warningSeconds      how long a late hazard's warning comes before it
 * @param lateIntervalSeconds the least time between two late hazards' warnings in one map
 */
public record HazardSettings(int maxSourcesPerMap, int maxSources, int flowSeconds, int warningSeconds,
        int lateIntervalSeconds) {

    public static final int MAX_SOURCES_PER_MAP = 256;
    public static final int MAX_SOURCES = 1024;
    public static final int MIN_FLOW_SECONDS = 1;
    public static final int MAX_FLOW_SECONDS = 3600;
    public static final int MIN_WARNING_SECONDS = 1;
    public static final int MAX_WARNING_SECONDS = 30;
    public static final int MIN_LATE_INTERVAL_SECONDS = 1;
    public static final int MAX_LATE_INTERVAL_SECONDS = 600;

    public static final HazardSettings DEFAULTS = new HazardSettings(MAX_SOURCES_PER_MAP, MAX_SOURCES, 300, 3, 5);

    public HazardSettings {
        maxSourcesPerMap = Math.clamp(maxSourcesPerMap, 0, MAX_SOURCES_PER_MAP);
        maxSources = Math.clamp(maxSources, 0, MAX_SOURCES);
        flowSeconds = Math.clamp(flowSeconds, MIN_FLOW_SECONDS, MAX_FLOW_SECONDS);
        warningSeconds = Math.clamp(warningSeconds, MIN_WARNING_SECONDS, MAX_WARNING_SECONDS);
        lateIntervalSeconds = Math.clamp(lateIntervalSeconds, MIN_LATE_INTERVAL_SECONDS, MAX_LATE_INTERVAL_SECONDS);
    }

    /** The caps and flow time given, with the default late hazard timing. */
    public HazardSettings(int maxSourcesPerMap, int maxSources, int flowSeconds) {
        this(maxSourcesPerMap, maxSources, flowSeconds, 3, 5);
    }

    /** Reads the settings, falling back to the defaults for a missing section or key. */
    public static HazardSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        HazardSettings d = DEFAULTS;
        return new HazardSettings(section.getInt("max-lava-sources-per-map", d.maxSourcesPerMap()),
                section.getInt("max-lava-sources", d.maxSources()),
                section.getInt("lava-flow-seconds", d.flowSeconds()),
                section.getInt("warning-seconds", d.warningSeconds()),
                section.getInt("late-interval-seconds", d.lateIntervalSeconds()));
    }
}
