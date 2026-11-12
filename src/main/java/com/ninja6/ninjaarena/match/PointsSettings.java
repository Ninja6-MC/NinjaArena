package com.ninja6.ninjaarena.match;

import org.bukkit.configuration.ConfigurationSection;

/** Personal point awards from {@code points}; zero disables an award. */
public record PointsSettings(int playerKill, int enemyKillPerCost, int roundSurvive, int roundWin) {

    public static final PointsSettings DEFAULTS = new PointsSettings(2, 1, 2, 6);

    public PointsSettings {
        playerKill = Math.max(0, playerKill);
        enemyKillPerCost = Math.max(0, enemyKillPerCost);
        roundSurvive = Math.max(0, roundSurvive);
        roundWin = Math.max(0, roundWin);
    }

    public static PointsSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        return new PointsSettings(section.getInt("player-kill", DEFAULTS.playerKill()),
                section.getInt("enemy-kill-per-cost", DEFAULTS.enemyKillPerCost()),
                section.getInt("round-survive", DEFAULTS.roundSurvive()),
                section.getInt("round-win", DEFAULTS.roundWin()));
    }
}
