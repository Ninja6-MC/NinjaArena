package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.stats.MatchRecord;
import com.ninja6.ninjaarena.stats.PlayerStats;
import com.ninja6.ninjaarena.stats.Stat;
import com.ninja6.ninjaarena.stats.StatsScope;
import com.ninja6.ninjaarena.stats.StatsSettings;
import com.ninja6.ninjaarena.stats.StatsStore;
import com.ninja6.ninjaarena.stats.StatsTracker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class StatsTrackerTest extends MatchFixture {

    private static final Instant ENDED = Instant.parse("2026-11-04T20:00:00Z");

    private final List<MatchRecord> records = new ArrayList<>();
    private StatsTracker tracker;

    private void track(Consumer<MatchRecord> sink) {
        tracker = new StatsTracker(sink, plugin.getServer()::getPlayer, () -> ENDED);
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, event -> {
            events.add(event);
            switch (event) {
                case MatchPhaseChangeEvent phase -> tracker.onPhase(phase);
                case PlayerEliminatedEvent out -> tracker.onEliminated(out);
                case RoundEndEvent end -> tracker.onRoundEnd(end);
                case MatchEndEvent end -> tracker.onMatchEnd(end);
                default -> { }
            }
        }, player -> readied.add(player.getUniqueId()));
    }

    private MatchView current() {
        return matches.match("dojo").orElseThrow();
    }

    @Test
    void killsDeathsRoundsWinsEnemiesAndOverrunSurvivalAreCountedPerPlayer() {
        track(records::add);
        startTwoOnTwo();
        // Round 1: Alice takes out team 2.
        ticks(2);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(ALICE));
        runNextTick();
        // Round 2 reaches OVERRUN at 5 seconds and is drawn when its 2-second limit runs out.
        ticks(1);
        tracker.enemyKilled(current(), ALICE);
        ticks(2);
        tracker.enemyKilled(current(), CAROL);
        ticks(3);
        assertEquals(MatchPhase.OVERRUN, current().phase());
        ticks(1);
        matches.died(player(ALICE), player(BOB));
        matches.died(player(DAVE), player(BOB));
        tracker.enemyKilled(current(), ALICE);
        ticks(1);
        assertEquals(List.of(CAROL, BOB), events(RoundEndEvent.class).getLast().result().survivors());
        ticks(1);
        assertEquals(List.of(1), events(MatchEndEvent.class).getLast().result().winners());

        assertEquals(1, records.size());
        assertEquals(ENDED, records.getFirst().endedAt());
        assertEquals(List.of(
                new PlayerStats(ALICE, "Alice", 1, 1, 2, 0, 1, 1, 1),
                new PlayerStats(CAROL, "Carol", 1, 1, 0, 1, 0, 2, 1),
                new PlayerStats(BOB, "Bob", 0, 0, 1, 0, 1, 2, 1),
                new PlayerStats(DAVE, "Dave", 0, 0, 0, 0, 2, 1, 1)), records.getFirst().players());
        // A hand-built arena is kept by its name, with no seed, and the result shows none.
        MatchResult result = events(MatchEndEvent.class).getLast().result();
        assertEquals(new MatchRecord.Details(result.id(), "COMPLETED", 2, List.of(1),
                List.of(MatchRecord.Played.handBuilt("dojo", 1, 2))), records.getFirst().match());
        assertEquals(List.of(new PlayedMap("dojo", null, 1, 2)), current().maps());
        assertTrue(players.get(ALICE).messages.stream().noneMatch(text -> text.contains("seed")),
                String.valueOf(players.get(ALICE).messages));
        // Nothing more is written when the match closes.
        ticks(2);
        assertEquals(1, records.size());
    }

    @Test
    void aStoppedMatchKeepsWhatWasEarnedButHasNoWinners() {
        track(records::add);
        startTwoOnTwo();
        ticks(2);
        matches.died(player(BOB), player(ALICE));
        matches.leave(player(ALICE), MatchService.Exit.COMMAND);
        players.get(ALICE).online = false;
        matches.close();
        assertEquals(MatchResult.Reason.STOPPED, events(MatchEndEvent.class).getLast().result().reason());
        assertEquals(List.of(
                new PlayerStats(ALICE, null, 0, 0, 1, 0, 0, 0, 1),
                new PlayerStats(CAROL, "Carol", 0, 0, 0, 0, 0, 0, 1),
                new PlayerStats(BOB, "Bob", 0, 0, 0, 0, 1, 0, 1),
                new PlayerStats(DAVE, "Dave", 0, 0, 0, 0, 0, 0, 1)), records.getFirst().players());
        MatchRecord.Details details = records.getFirst().match();
        assertEquals("STOPPED", details.reason());
        assertEquals(List.of(), details.winners());
        assertEquals(List.of(MatchRecord.Played.handBuilt("dojo", 1, 1)), details.maps());
    }

    @Test
    void playersWhoLeaveBeforeTheRoundOrMatchIsDecidedGetNoWin() {
        track(records::add);
        startTwoOnTwo();
        ticks(2);
        matches.leave(player(BOB), MatchService.Exit.COMMAND);
        matches.leave(player(DAVE), MatchService.Exit.COMMAND);
        runNextTick();
        ticks(1);
        assertEquals(MatchResult.Reason.FORFEIT, events(MatchEndEvent.class).getLast().result().reason());
        List<PlayerStats> players = records.getFirst().players();
        assertEquals(new PlayerStats(ALICE, "Alice", 1, 1, 0, 0, 0, 0, 1), players.get(0));
        assertEquals(new PlayerStats(CAROL, "Carol", 1, 1, 0, 0, 0, 0, 1), players.get(1));
        assertEquals(new PlayerStats(BOB, "Bob", 0, 0, 0, 0, 0, 0, 1), players.get(2));
        assertEquals(new PlayerStats(DAVE, "Dave", 0, 0, 0, 0, 0, 0, 1), players.get(3));
    }

    @Test
    void finishedMatchesReachTheStoreAndCanBeRankedAfterwards() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        StatsStore store = StatsStore.open(StatsSettings.DEFAULTS, dir.resolve("plugin"), logger);
        try {
            track(store::record);
            for (int match = 0; match < 2; match++) {
                startTwoOnTwo();
                ticks(2);
                matches.leave(player(BOB), MatchService.Exit.COMMAND);
                matches.leave(player(DAVE), MatchService.Exit.COMMAND);
                runNextTick();
                ticks(1 + 2);
                players.values().forEach(fake -> fake.messages.clear());
            }
            store.flush().get(10, TimeUnit.SECONDS);
            PlayerStats alice = store.stats(ALICE, StatsScope.CURRENT_SEASON).get(10, TimeUnit.SECONDS).orElseThrow();
            assertEquals(2, alice.wins());
            assertEquals(2, alice.matchesPlayed());
            assertEquals(List.of(ALICE, CAROL), store.top(Stat.WINS, StatsScope.ALL_TIME, 10)
                    .get(10, TimeUnit.SECONDS).stream().map(entry -> entry.player()).toList());
        } finally {
            store.close();
        }
    }
}
