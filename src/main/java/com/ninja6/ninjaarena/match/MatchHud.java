package com.ninja6.ninjaarena.match;

import com.ninja6.ninjaarena.director.EnemyKind;
import com.ninja6.ninjaarena.director.SpawnGate;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Round feedback, read from the match on the server thread without advancing it. */
public final class MatchHud implements Listener {

    private static final Sound SPAWN = Sound.sound(Key.key("entity.enderman.teleport"), Sound.Source.HOSTILE, 0.35f, 1.2f);
    private static final Sound ROUND_END = Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 0.6f, 1f);
    private static final Title.Times TITLE_TIMES = Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2),
            Duration.ofMillis(400));

    private static final class Display {
        final BossBar bar = BossBar.bossBar(Component.empty(), 1, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        final Map<UUID, Player> viewers = new HashMap<>();
    }

    private final Supplier<List<MatchView>> matches;
    private final Function<UUID, Player> players;
    private final MatchStandings scores;
    private final ToIntFunction<MatchView> enemies;
    private final Map<MatchView, Display> displays = new IdentityHashMap<>();
    private BukkitTask ticker;
    private boolean closed;

    public MatchHud(Supplier<List<MatchView>> matches, Function<UUID, Player> players, MatchStandings scores,
            ToIntFunction<MatchView> enemies) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.players = Objects.requireNonNull(players, "players");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.enemies = Objects.requireNonNull(enemies, "enemies");
    }

    /** Start after the match ticker so each refresh sees the current second. */
    public void start(Plugin plugin) {
        if (!closed && ticker == null) {
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
        }
    }

    public void tick() {
        if (closed) {
            return;
        }
        List<MatchView> live = matches.get();
        for (MatchView match : new ArrayList<>(displays.keySet())) {
            if (!live.contains(match)) {
                remove(match);
            }
        }
        for (MatchView match : live) {
            refresh(match);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPhase(MatchPhaseChangeEvent event) {
        if (closed) {
            return;
        }
        refresh(event.match());
        if (event.to() == MatchPhase.COMBAT) {
            title(event.match(), "Fight!", "Round " + event.match().round(), NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundStart(RoundStartEvent event) {
        if (!closed) {
            title(event.match(), (event.isExtra() ? "Extra round " : "Round ") + event.round(),
                    "Prepare for the fight", NamedTextColor.GOLD);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(RoundEndEvent event) {
        if (!closed) {
            title(event.match(), event.result().isDraw() ? "Round drawn" : "Team " + event.result().winner() + " wins!",
                    "Round " + event.result().round() + " complete", NamedTextColor.GOLD);
            each(event.match(), player -> player.playSound(ROUND_END));
            refresh(event.match());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeave(PlayerLeaveMatchEvent event) {
        Display display = displays.get(event.match());
        if (display != null) {
            removeViewer(display, event.player());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchEnd(MatchEndEvent event) {
        remove(event.match());
    }

    /** Preserve the stability gate and add a cue only after a successful enemy spawn. */
    public SpawnGate withSpawnCues(SpawnGate gate) {
        return new SpawnGate() {
            @Override
            public double intensity(MatchView match, double target) {
                return gate.intensity(match, target);
            }

            @Override
            public boolean maySpawn(MatchView match) {
                return gate.maySpawn(match);
            }

            @Override
            public void spawned(MatchView match, EnemyKind kind) {
                gate.spawned(match, kind);
                if (!closed) {
                    each(match, player -> player.playSound(SPAWN));
                }
            }
        };
    }

    public void close() {
        closed = true;
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (MatchView match : new ArrayList<>(displays.keySet())) {
            remove(match);
        }
    }

    private void refresh(MatchView match) {
        if (match.phase() == MatchPhase.WAITING || match.phase() == MatchPhase.MATCH_END) {
            remove(match);
            return;
        }
        Display display = displays.computeIfAbsent(match, ignored -> new Display());
        String round = (match.isExtraRound() ? "Extra round " : "Round ") + match.round();
        int duration = switch (match.phase()) {
            case PREP -> match.settings().prepSeconds();
            case COMBAT -> match.settings().roundSeconds();
            case OVERRUN -> match.settings().overrunLimitSeconds();
            case ROUND_END -> match.settings().roundEndSeconds();
            default -> 0;
        };
        String label = switch (match.phase()) {
            case PREP -> round + " - Prep " + clock(match.secondsLeft());
            case COMBAT -> round + " - " + clock(match.secondsLeft());
            case OVERRUN -> round + " - OVERRUN" + (duration > 0 ? " " + clock(match.secondsLeft()) : "");
            case ROUND_END -> "Next round in " + match.secondsLeft() + "s";
            default -> "";
        };
        display.bar.name(Component.text(label));
        display.bar.color(match.phase() == MatchPhase.OVERRUN ? BossBar.Color.RED : BossBar.Color.YELLOW);
        display.bar.progress(duration == 0 ? 1 : Math.clamp((float) match.secondsLeft() / duration, 0, 1));
        for (UUID id : new ArrayList<>(display.viewers.keySet())) {
            Player player = players.apply(id);
            if (!match.contains(id) || player == null || !player.isOnline()) {
                removeViewer(display, id);
            }
        }
        List<String> totals = new ArrayList<>();
        for (int team : match.startingTeams()) {
            totals.add("T" + team + ": " + scores.score(match, team));
        }
        Component status = Component.text(String.join("  ", totals) + "  |  Enemies left: "
                + Math.max(0, enemies.applyAsInt(match)), NamedTextColor.YELLOW);
        each(match, player -> {
            if (display.viewers.putIfAbsent(player.getUniqueId(), player) == null) {
                player.showBossBar(display.bar);
            }
            player.sendActionBar(status);
        });
    }

    private void title(MatchView match, String title, String subtitle, NamedTextColor color) {
        Title message = Title.title(Component.text(title, color), Component.text(subtitle), TITLE_TIMES);
        each(match, player -> player.showTitle(message));
    }

    private void each(MatchView match, Consumer<Player> action) {
        for (UUID id : match.players()) {
            Player player = players.apply(id);
            if (player != null && player.isOnline()) {
                action.accept(player);
            }
        }
    }

    private void remove(MatchView match) {
        Display display = displays.remove(match);
        if (display != null) {
            for (UUID id : new ArrayList<>(display.viewers.keySet())) {
                removeViewer(display, id);
            }
        }
    }

    private void removeViewer(Display display, UUID id) {
        Player player = display.viewers.remove(id);
        if (player != null) {
            player.hideBossBar(display.bar);
            player.sendActionBar(Component.empty());
            player.clearTitle();
        }
    }

    private static String clock(int seconds) {
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }
}
