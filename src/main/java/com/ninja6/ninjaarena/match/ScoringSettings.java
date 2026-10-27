package com.ninja6.ninjaarena.match;

import org.bukkit.configuration.ConfigurationSection;

/** Non-negative point awards from the {@code scoring} config section; zero disables an award. */
public record ScoringSettings(int roundWinPoints, int playerKillPoints) {

    public static final ScoringSettings DEFAULTS = new ScoringSettings(5, 2);

    public ScoringSettings {
        roundWinPoints = Math.max(0, roundWinPoints);
        playerKillPoints = Math.max(0, playerKillPoints);
    }

    public static ScoringSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        return new ScoringSettings(section.getInt("round-win-points", DEFAULTS.roundWinPoints()),
                section.getInt("player-kill-points", DEFAULTS.playerKillPoints()));
    }
}
