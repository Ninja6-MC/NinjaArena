package com.ninja6.ninjaarena.stats;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.MapSeed;
import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchResult;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.PlayedMap;
import com.ninja6.ninjaarena.match.RoundResult;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Counts each player's stats through the match events and hands one {@link MatchRecord}
 * per match to the store when the match ends. Nothing is written during a match. Runs on the
 * server thread only.
 *
 * <p>Kills and deaths count as they happen; a player who leaves keeps what they earned. A
 * round win counts for the winning team's players still in the match, and a match win for the
 * winning teams' players still in it when it ends, so an abandoned or stopped match has no
 * winners. Overrun survival is the seconds from the start of a round's OVERRUN until the
 * player died in it or the round ended with them alive.
 */
public final class StatsTracker implements Listener {

    private static final class Counts {
        int roundsWon;
        int playerKills;
        int enemyKills;
        int deaths;
        int bestOverrun;
    }

    private static final class Tally {
        final Map<UUID, Counts> players = new HashMap<>();
        /** Round seconds when the current round's OVERRUN began, or -1. */
        int overrunStart = -1;

        Counts of(UUID player) {
            return players.computeIfAbsent(player, id -> new Counts());
        }
    }

    private final Map<MatchView, Tally> tallies = new IdentityHashMap<>();
    private final Consumer<MatchRecord> sink;
    private final Function<UUID, Player> players;
    private final Supplier<Instant> clock;

    public StatsTracker(Consumer<MatchRecord> sink, Function<UUID, Player> players, Supplier<Instant> clock) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.players = Objects.requireNonNull(players, "players");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPhase(MatchPhaseChangeEvent event) {
        if (event.to() == MatchPhase.OVERRUN) {
            tally(event.match()).overrunStart = event.match().roundElapsedSeconds();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEliminated(PlayerEliminatedEvent event) {
        if (!event.isDeath()) {
            return;
        }
        MatchView match = event.match();
        Tally tally = tally(match);
        Counts dead = tally.of(event.player());
        dead.deaths++;
        if (match.phase() == MatchPhase.OVERRUN && tally.overrunStart >= 0) {
            dead.bestOverrun = Math.max(dead.bestOverrun, match.roundElapsedSeconds() - tally.overrunStart);
        }
        event.killer().ifPresent(killer -> {
            int team = match.teamOf(killer);
            if (match.contains(killer) && team > 0 && team != event.team() && !killer.equals(event.player())) {
                tally.of(killer).playerKills++;
            }
        });
    }

    /**
     * An enemy of the match's director was killed by {@code killer}. Counts only during a fight
     * and only for a player still alive in the round, as enemy kill points do.
     */
    public void enemyKilled(MatchView match, UUID killer) {
        if (match.phase().isFighting() && match.isAlive(killer)) {
            tally(match).of(killer).enemyKills++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(RoundEndEvent event) {
        MatchView match = event.match();
        Tally tally = tally(match);
        RoundResult result = event.result();
        if (!result.isDraw()) {
            for (UUID player : match.playersOf(result.winner())) {
                tally.of(player).roundsWon++;
            }
        }
        if (result.reachedOverrun() && tally.overrunStart >= 0) {
            int survived = Math.max(0, result.seconds() - tally.overrunStart);
            for (UUID player : result.survivors()) {
                Counts counts = tally.of(player);
                counts.bestOverrun = Math.max(counts.bestOverrun, survived);
            }
        }
        tally.overrunStart = -1;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchEnd(MatchEndEvent event) {
        MatchView match = event.match();
        Tally removed = tallies.remove(match);
        Tally tally = removed == null ? new Tally() : removed;
        MatchResult result = event.result();
        Set<UUID> winners = new HashSet<>();
        for (int team : result.winners()) {
            winners.addAll(match.playersOf(team));
        }
        Map<UUID, PlayerStats> record = new LinkedHashMap<>();
        for (MatchResult.Standing standing : result.standings()) {
            for (UUID player : standing.players()) {
                record.computeIfAbsent(player, id -> stats(id, tally.players.get(id), winners.contains(id)));
            }
        }
        // Defensive: count anyone who earned something without being on the starting roster.
        for (var entry : tally.players.entrySet()) {
            record.computeIfAbsent(entry.getKey(), id -> stats(id, entry.getValue(), false));
        }
        if (!record.isEmpty()) {
            sink.accept(new MatchRecord(clock.get(), new ArrayList<>(record.values()), details(match, result)));
        }
    }

    /**
     * The match's own row. A generated map is kept by its seed and difficulty, never by its
     * slot name, which names another map in the next match.
     */
    static MatchRecord.Details details(MatchView match, MatchResult result) {
        List<MatchRecord.Played> maps = new ArrayList<>();
        for (PlayedMap played : match.maps()) {
            maps.add(played(played.arena(), played.seed(), played.firstRound(), played.rounds()));
        }
        if (maps.isEmpty()) {
            // Ended before its first round: it counts as played on its map, with no rounds of its own.
            maps.add(played(match.arena(), null, 1, 1));
        }
        return new MatchRecord.Details(result.id(), result.reason().name(), match.teamCount(), result.winners(),
                maps);
    }

    private static MatchRecord.Played played(String arena, MapSeed seed, int firstRound, int rounds) {
        if (seed != null) {
            return MatchRecord.Played.generated(seed.seed(), seed.difficulty(), firstRound, rounds);
        }
        String name = Arena.normalizeName(arena);
        return Arena.isValidName(name) ? MatchRecord.Played.handBuilt(name, firstRound, rounds)
                : new MatchRecord.Played(MatchRecord.GENERATED, null, null, firstRound, rounds);
    }

    private PlayerStats stats(UUID id, Counts counts, boolean won) {
        Counts earned = counts == null ? new Counts() : counts;
        Player online = players.apply(id);
        String name = online == null ? null : online.getName();
        return new PlayerStats(id, name, won ? 1 : 0, earned.roundsWon, earned.playerKills, earned.enemyKills,
                earned.deaths, earned.bestOverrun, 1);
    }

    private Tally tally(MatchView match) {
        return tallies.computeIfAbsent(match, view -> new Tally());
    }
}
