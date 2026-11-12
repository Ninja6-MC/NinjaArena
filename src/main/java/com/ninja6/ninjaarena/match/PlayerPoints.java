package com.ninja6.ninjaarena.match;

import com.ninja6.ninjaarena.match.MatchUpdate.Cause;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Personal points within a match, independent of team standings. Server thread only. */
public final class PlayerPoints implements Listener {

    private final Map<MatchView, Map<UUID, Integer>> points = new IdentityHashMap<>();
    private final Map<MatchView, PointsSettings> matchSettings = new IdentityHashMap<>();
    private PointsSettings settings;

    public PlayerPoints(PointsSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Returns zero after leaving or match end; points carry over between all rounds. */
    public int points(MatchView match, UUID player) {
        Map<UUID, Integer> table = points.get(match);
        return table == null ? 0 : table.getOrDefault(player, 0);
    }

    /** Takes a positive price atomically on the server thread; never creates a ledger. */
    public boolean spend(MatchView match, UUID player, int price) {
        if (price <= 0 || !match.contains(player) || match.result().isPresent() || points(match, player) < price) {
            return false;
        }
        points.get(match).computeIfPresent(player, (id, balance) -> balance - price);
        return true;
    }

    /** Returns a cancelled purchase only while its original personal ledger still exists. */
    public void refund(MatchView match, UUID player, int price) {
        Map<UUID, Integer> table = points.get(match);
        if (price > 0 && table != null) {
            table.computeIfPresent(player, (id, balance) -> (int) Math.min(Integer.MAX_VALUE, (long) balance + price));
        }
    }

    /** Preserve the old awards for every running match, including those with no awards yet. */
    public void setSettings(PointsSettings settings, List<MatchView> running) {
        Objects.requireNonNull(settings, "settings");
        for (MatchView match : running) {
            if (match.result().isEmpty()) {
                matchSettings.putIfAbsent(match, this.settings);
            }
        }
        this.settings = settings;
    }

    @EventHandler
    public void onEliminated(PlayerEliminatedEvent event) {
        if (event.cause() != Cause.DIED) {
            return;
        }
        event.killer().ifPresent(killer -> {
            int team = event.match().teamOf(killer);
            if (event.match().contains(killer) && team > 0 && team != event.team()
                    && !killer.equals(event.player())) {
                award(event.match(), killer, awards(event.match()).playerKill());
            }
        });
    }

    /** Called by the enemy lifecycle with the same configured cost used for team scoring. */
    public void enemyKilled(MatchView match, UUID killer, int cost) {
        if (match.phase().isFighting() && match.isAlive(killer)) {
            award(match, killer, (long) Math.max(0, cost) * awards(match).enemyKillPerCost());
        }
    }

    @EventHandler
    public void onRoundEnd(RoundEndEvent event) {
        MatchView match = event.match();
        PointsSettings awards = awards(match);
        for (UUID player : event.result().survivors()) {
            award(match, player, awards.roundSurvive());
        }
        if (!event.result().isDraw()) {
            List<UUID> winners = match.playersOf(event.result().winner());
            if (!winners.isEmpty()) {
                int share = awards.roundWin() / winners.size();
                int remainder = awards.roundWin() % winners.size();
                for (int i = 0; i < winners.size(); i++) {
                    award(match, winners.get(i), share + (i < remainder ? 1 : 0));
                }
            }
        }
    }

    @EventHandler
    public void onLeave(PlayerLeaveMatchEvent event) {
        Map<UUID, Integer> table = points.get(event.match());
        if (table != null) {
            table.remove(event.player());
        }
    }

    @EventHandler
    public void onMatchEnd(MatchEndEvent event) {
        points.remove(event.match());
        matchSettings.remove(event.match());
    }

    private PointsSettings awards(MatchView match) {
        return matchSettings.getOrDefault(match, settings);
    }

    private void award(MatchView match, UUID player, long amount) {
        if (amount <= 0 || !match.contains(player) || match.result().isPresent()
                || match.phase() == MatchPhase.WAITING || match.phase() == MatchPhase.MATCH_END) {
            return;
        }
        Map<UUID, Integer> table = points.computeIfAbsent(match, ignored -> new HashMap<>());
        table.put(player, (int) Math.min(Integer.MAX_VALUE, table.getOrDefault(player, 0) + amount));
    }
}
