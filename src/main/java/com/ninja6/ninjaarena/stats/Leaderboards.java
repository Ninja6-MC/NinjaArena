package com.ninja6.ninjaarena.stats;

import static com.ninja6.ninjaarena.message.Messages.msg;
import static com.ninja6.ninjaarena.message.Messages.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;

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
        return entry.name() == null || entry.name().isBlank() ? text("leaderboard.unknown-player") : entry.name();
    }

    public static List<Component> lines(Stat stat, String period, List<LeaderboardEntry> entries) {
        List<Component> lines = new ArrayList<>();
        lines.add(msg("leaderboard.heading", "limit", LIMIT, "stat", stat.column().replace('_', ' '),
                "period", period));
        if (entries.isEmpty()) {
            lines.add(msg("leaderboard.empty"));
        }
        for (LeaderboardEntry entry : entries) {
            lines.add(msg(stat == Stat.BEST_OVERRUN_SECONDS ? "leaderboard.line-seconds" : "leaderboard.line",
                    "rank", entry.rank(), "player", name(entry), "value", entry.value()));
        }
        return List.copyOf(lines);
    }
}
