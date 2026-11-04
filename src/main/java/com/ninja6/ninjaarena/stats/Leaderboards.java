package com.ninja6.ninjaarena.stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class Leaderboards {

    public static final int LIMIT = 10;
    public static final List<String> STAT_NAMES = Arrays.stream(Stat.values()).map(Stat::column).toList();

    private Leaderboards() {
    }

    public static Stat stat(String name) {
        int index = STAT_NAMES.indexOf(name.toLowerCase(Locale.ROOT));
        return index < 0 ? null : Stat.values()[index];
    }

    public static String name(LeaderboardEntry entry) {
        return entry.name() == null || entry.name().isBlank() ? "Unknown player" : entry.name();
    }

    public static List<String> lines(Stat stat, String period, List<LeaderboardEntry> entries) {
        String heading = "Top " + LIMIT + " - " + stat.column().replace('_', ' ') + " (" + period + ")";
        List<String> lines = new ArrayList<>();
        lines.add(heading);
        if (entries.isEmpty()) {
            lines.add("No scores recorded yet.");
        }
        for (LeaderboardEntry entry : entries) {
            lines.add(entry.rank() + ". " + name(entry) + " - " + entry.value()
                    + (stat == Stat.BEST_OVERRUN_SECONDS ? " s" : ""));
        }
        return List.copyOf(lines);
    }
}
