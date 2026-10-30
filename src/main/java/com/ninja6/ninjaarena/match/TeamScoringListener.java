package com.ninja6.ninjaarena.match;

import com.ninja6.ninjaarena.match.MatchUpdate.Cause;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Awards points through the match events and shows the final points table before return. */
public final class TeamScoringListener implements Listener {

    private final TeamScores scores;
    private ScoringSettings settings;
    private final Map<MatchView, ScoringSettings> matchSettings = new IdentityHashMap<>();
    private final Function<UUID, Player> players;

    public TeamScoringListener(TeamScores scores, ScoringSettings settings, Function<UUID, Player> players) {
        this.scores = Objects.requireNonNull(scores, "scores");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.players = Objects.requireNonNull(players, "players");
    }

    /** Existing matches keep their awards even if no award has been made yet. */
    public void setSettings(ScoringSettings settings, List<MatchView> running) {
        for (MatchView match : running) {
            if (match.result().isEmpty()) {
                matchSettings.putIfAbsent(match, this.settings);
            }
        }
        this.settings = Objects.requireNonNull(settings, "settings");
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
                scores.addPoints(event.match(), team, matchSettings.getOrDefault(event.match(), settings).playerKillPoints());
            }
        });
    }

    @EventHandler
    public void onRoundEnd(RoundEndEvent event) {
        if (!event.result().isDraw()) {
            scores.addPoints(event.match(), event.result().winner(),
                    matchSettings.getOrDefault(event.match(), settings).roundWinPoints());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchEnd(MatchEndEvent event) {
        try {
            String table = event.result().standings().stream().map(standing -> "Team " + standing.team()
                    + ": " + standing.score() + (standing.score() == 1 ? " point" : " points")
                    + " (" + standing.roundsWon() + (standing.roundsWon() == 1 ? " round" : " rounds") + " won)")
                    .collect(Collectors.joining(", "));
            Component message = Component.text("Final scores: " + table + ".", NamedTextColor.GOLD);
            for (UUID id : event.match().players()) {
                Player player = players.apply(id);
                if (player != null && player.isOnline()) {
                    player.sendMessage(message);
                }
            }
        } finally {
            scores.finish(event.match());
            matchSettings.remove(event.match());
        }
    }
}
