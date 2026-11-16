package com.ninja6.ninjaarena.lobby;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * {@code lobby.pads} in config.yml: how long a player stands on a room's pad before it acts,
 * and the fog drawn on pads for nearby, recently active players.
 *
 * @param enabled             whether rooms get pads at all
 * @param joinSeconds         seconds on the plate before the pad acts, 1 to 30
 * @param fog                 whether fog is drawn
 * @param fogDistance         blocks from a viewer to a pad's middle within which they see its fog
 * @param activeSeconds       a viewer sees fog only this long after they last moved, looked,
 *                            chatted or used a command
 * @param fogIntervalTicks    ticks between two draws
 * @param fogSize             the dust's size
 * @param perBlock            particles on each frame block in a draw, sent as one packet per block
 * @param maxParticles        most particles one Java viewer gets in a draw, counted as the
 *                            particles its client spawns, not the packets
 * @param bedrockMaxParticles most particles one Bedrock viewer gets in a draw; Geyser sends
 *                            each one as its own packet
 * @param colours             the fog colour of each room state, as {@code 0xRRGGBB}
 */
public record PadSettings(boolean enabled, int joinSeconds, boolean fog, int fogDistance, int activeSeconds,
        int fogIntervalTicks, double fogSize, int perBlock, int maxParticles, int bedrockMaxParticles,
        Map<PadState, Integer> colours) {

    public static final String SECTION = "lobby.pads";
    public static final int DEFAULT_JOIN_SECONDS = 5;
    public static final int MIN_JOIN_SECONDS = 1;
    public static final int MAX_JOIN_SECONDS = 30;
    public static final int DEFAULT_DISTANCE = 16;
    public static final int DEFAULT_ACTIVE_SECONDS = 30;
    public static final int DEFAULT_INTERVAL_TICKS = 7;
    public static final double DEFAULT_SIZE = 2.0;
    public static final double MIN_SIZE = 0.5;
    public static final double MAX_SIZE = 4.0;
    public static final int DEFAULT_PER_BLOCK = 6;
    public static final int MAX_PER_BLOCK = 12;
    /** Three whole pads of the default fog: 24 frame blocks of 6 particles each. */
    public static final int DEFAULT_MAX_PARTICLES = 432;
    public static final int MIN_MAX_PARTICLES = 24;
    public static final int MAX_MAX_PARTICLES = 1152;
    /** Two particles on each of one pad's 24 frame blocks. */
    public static final int DEFAULT_BEDROCK_MAX_PARTICLES = 48;
    public static final int MAX_BEDROCK_MAX_PARTICLES = 288;

    /** The bundled colours. */
    public static final Map<PadState, Integer> DEFAULT_COLOURS = Map.of(
            PadState.OPEN, 0x55FF55,
            PadState.ASK, 0xFFC83D,
            PadState.INVITE, 0xB86BFF,
            PadState.FULL, 0xFF5555,
            PadState.PLAYING, 0x5AA0FF);

    public static final PadSettings DEFAULTS = new PadSettings(true, DEFAULT_JOIN_SECONDS, true, DEFAULT_DISTANCE,
            DEFAULT_ACTIVE_SECONDS, DEFAULT_INTERVAL_TICKS, DEFAULT_SIZE, DEFAULT_PER_BLOCK, DEFAULT_MAX_PARTICLES,
            DEFAULT_BEDROCK_MAX_PARTICLES, DEFAULT_COLOURS);

    public PadSettings {
        colours = Map.copyOf(colours);
    }

    /** Reads {@value #SECTION}; a missing section is the defaults. Values out of range are clamped, with a warning. */
    public static PadSettings from(ConfigurationSection pads, Consumer<String> warnings) {
        if (pads == null) {
            return DEFAULTS;
        }
        ConfigurationSection fog = pads.getConfigurationSection("fog");
        Map<PadState, Integer> colours = new EnumMap<>(DEFAULT_COLOURS);
        ConfigurationSection colourSection = fog == null ? null : fog.getConfigurationSection("colours");
        if (colourSection != null) {
            for (PadState state : PadState.values()) {
                String key = state.key();
                // getString(key) also reads the bundled defaults; getString(key, "") would not.
                String value = colourSection.getString(key);
                if (value == null) {
                    continue;
                }
                Integer parsed = colour(value);
                if (parsed == null) {
                    warnings.accept(SECTION + ".fog.colours." + key + " must be a colour like \"#55FF55\"; using the"
                            + " default.");
                } else {
                    colours.put(state, parsed);
                }
            }
        }
        return new PadSettings(pads.getBoolean("enabled", true),
                ranged(pads, "join-seconds", DEFAULT_JOIN_SECONDS, MIN_JOIN_SECONDS, MAX_JOIN_SECONDS, warnings),
                fog == null || fog.getBoolean("enabled", true),
                ranged(fog, "distance", DEFAULT_DISTANCE, 4, 64, warnings),
                ranged(fog, "active-seconds", DEFAULT_ACTIVE_SECONDS, 5, 600, warnings),
                ranged(fog, "interval-ticks", DEFAULT_INTERVAL_TICKS, 5, 40, warnings),
                size(fog, warnings),
                ranged(fog, "per-block", DEFAULT_PER_BLOCK, 1, MAX_PER_BLOCK, warnings),
                ranged(fog, "max-particles", DEFAULT_MAX_PARTICLES, MIN_MAX_PARTICLES, MAX_MAX_PARTICLES, warnings),
                ranged(fog, "bedrock-max-particles", DEFAULT_BEDROCK_MAX_PARTICLES, 0, MAX_BEDROCK_MAX_PARTICLES,
                        warnings),
                colours);
    }

    /** The colour of a state. */
    public int colour(PadState state) {
        return colours.getOrDefault(state, DEFAULT_COLOURS.get(state));
    }

    private static int ranged(ConfigurationSection section, String key, int fallback, int min, int max,
            Consumer<String> warnings) {
        if (section == null || !section.contains(key)) {
            return fallback;
        }
        Object value = section.get(key);
        String path = section.getCurrentPath() + "." + key;
        if (!(value instanceof Integer number)) {
            warnings.accept(path + " must be a whole number from " + min + " to " + max + "; using " + fallback + ".");
            return fallback;
        }
        if (number < min || number > max) {
            int clamped = Math.clamp(number, min, max);
            warnings.accept(path + " must be from " + min + " to " + max + "; using " + clamped + ".");
            return clamped;
        }
        return number;
    }

    private static double size(ConfigurationSection fog, Consumer<String> warnings) {
        if (fog == null || !fog.contains("size")) {
            return DEFAULT_SIZE;
        }
        Object value = fog.get("size");
        String path = fog.getCurrentPath() + ".size";
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            warnings.accept(path + " must be a number from " + MIN_SIZE + " to " + MAX_SIZE + "; using "
                    + DEFAULT_SIZE + ".");
            return DEFAULT_SIZE;
        }
        double size = number.doubleValue();
        if (size < MIN_SIZE || size > MAX_SIZE) {
            double clamped = Math.clamp(size, MIN_SIZE, MAX_SIZE);
            warnings.accept(path + " must be from " + MIN_SIZE + " to " + MAX_SIZE + "; using " + clamped + ".");
            return clamped;
        }
        return size;
    }

    private static Integer colour(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (!hex.matches("[0-9A-Fa-f]{6}")) {
            return null;
        }
        return Integer.parseInt(hex, 16);
    }
}
