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
 * @param perBlock            particles on each ring block in a draw
 * @param maxParticles        most particles one Java viewer gets in a draw
 * @param bedrockMaxParticles most particles one Bedrock viewer gets in a draw; Geyser sends
 *                            each one as its own packet
 * @param colours             the fog colour of each room state, as {@code 0xRRGGBB}
 */
public record PadSettings(boolean enabled, int joinSeconds, boolean fog, int fogDistance, int activeSeconds,
        int fogIntervalTicks, int perBlock, int maxParticles, int bedrockMaxParticles,
        Map<PadState, Integer> colours) {

    public static final String SECTION = "lobby.pads";
    public static final int DEFAULT_JOIN_SECONDS = 5;
    public static final int MIN_JOIN_SECONDS = 1;
    public static final int MAX_JOIN_SECONDS = 30;
    public static final int DEFAULT_DISTANCE = 16;
    public static final int DEFAULT_ACTIVE_SECONDS = 30;
    public static final int DEFAULT_INTERVAL_TICKS = 10;
    public static final int DEFAULT_PER_BLOCK = 1;
    public static final int DEFAULT_MAX_PARTICLES = 48;
    public static final int DEFAULT_BEDROCK_MAX_PARTICLES = 16;

    /** The bundled colours. */
    public static final Map<PadState, Integer> DEFAULT_COLOURS = Map.of(
            PadState.OPEN, 0x55FF55,
            PadState.ASK, 0xFFC83D,
            PadState.INVITE, 0xB86BFF,
            PadState.FULL, 0xFF5555,
            PadState.PLAYING, 0x5AA0FF);

    public static final PadSettings DEFAULTS = new PadSettings(true, DEFAULT_JOIN_SECONDS, true, DEFAULT_DISTANCE,
            DEFAULT_ACTIVE_SECONDS, DEFAULT_INTERVAL_TICKS, DEFAULT_PER_BLOCK, DEFAULT_MAX_PARTICLES,
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
                if (!colourSection.contains(key)) {
                    continue;
                }
                String value = colourSection.getString(key, "");
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
                ranged(fog, "per-block", DEFAULT_PER_BLOCK, 1, 4, warnings),
                ranged(fog, "max-particles", DEFAULT_MAX_PARTICLES, 8, 128, warnings),
                ranged(fog, "bedrock-max-particles", DEFAULT_BEDROCK_MAX_PARTICLES, 0, 128, warnings),
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

    private static Integer colour(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (!hex.matches("[0-9A-Fa-f]{6}")) {
            return null;
        }
        return Integer.parseInt(hex, 16);
    }
}
