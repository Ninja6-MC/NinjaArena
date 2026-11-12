package com.ninja6.ninjaarena.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class LeaderboardsTest {

    @Test
    void formatsEmptyAllTimeBoard() {
        assertEquals(List.of("Top 10 - wins (all-time)", "No scores recorded yet."),
                plain(Leaderboards.lines(Stat.WINS, "all-time", List.of())));
    }

    @Test
    void formatsSeasonTiesWithPlainTextAndUnits() {
        assertEquals(List.of("Top 10 - best overrun seconds (Season 1)",
                "1. .BedrockPlayer - 41 s", "2. Unknown player - 41 s"),
                plain(Leaderboards.lines(Stat.BEST_OVERRUN_SECONDS, "Season 1",
                        List.of(new LeaderboardEntry(1, new UUID(0, 1), ".BedrockPlayer", 41),
                                new LeaderboardEntry(2, new UUID(0, 2), null, 41)))));
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }
}
