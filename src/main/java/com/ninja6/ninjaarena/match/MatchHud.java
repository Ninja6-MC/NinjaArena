package com.ninja6.ninjaarena.match;

import static com.ninja6.ninjaarena.message.Messages.msg;
import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.director.EnemyKind;
import com.ninja6.ninjaarena.director.SpawnGate;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.sound.Sounds;
import com.ninja6.ninjaarena.sound.Sounds.Cue;
import com.ninja6.ninjaarena.tool.NinjaTool;
import com.ninja6.ninjaarena.tool.NinjaTools.ThrowState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Round feedback, read from the match on the server thread without advancing it. */
public final class MatchHud implements Listener {

    private static final Title.Times TITLE_TIMES = Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2),
            Duration.ofMillis(400));

    private static final class Display {
        final BossBar bar = BossBar.bossBar(Component.empty(), 1, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        final Map<UUID, Player> viewers = new HashMap<>();
    }

    private final Supplier<List<MatchView>> matches;
    private final Function<UUID, Player> players;
    private final MatchStandings scores;
    private final PlayerPoints points;
    private final ToIntFunction<MatchView> enemies;
    private final BiFunction<UUID, NinjaTool, ThrowState> tools;
    private final Map<MatchView, Display> displays = new IdentityHashMap<>();
    private Sounds sounds;
    private Consumer<MatchView> cooldowns = match -> { };

    private BukkitTask ticker;
    private boolean closed;

    public MatchHud(Supplier<List<MatchView>> matches, Function<UUID, Player> players, MatchStandings scores, PlayerPoints points,
            ToIntFunction<MatchView> enemies, BiFunction<UUID, NinjaTool, ThrowState> tools) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.players = Objects.requireNonNull(players, "players");
        this.sounds = new Sounds(players);
        this.scores = Objects.requireNonNull(scores, "scores");
        this.points = Objects.requireNonNull(points, "points");
        this.enemies = Objects.requireNonNull(enemies, "enemies");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    public void setSounds(Sounds sounds) {
        this.sounds = sounds;
    }

    /** Start after the match ticker so each refresh sees the current second. */
    public void start(Plugin plugin) {
        if (!closed && ticker == null) {
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
        }
    }

    public void setCooldownCheck(Consumer<MatchView> cooldowns) {
        this.cooldowns = cooldowns;
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
            cooldowns.accept(match);
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
            title(event.match(), msg("hud.fight-title"), msg("hud.fight-subtitle", "round", event.match().round()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundStart(RoundStartEvent event) {
        if (!closed) {
            title(event.match(), msg(event.isExtra() ? "hud.extra-round-title" : "hud.round-title",
                    "round", event.round()), msg("hud.round-subtitle"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(RoundEndEvent event) {
        if (!closed) {
            title(event.match(), event.result().isDraw() ? msg("hud.draw-title")
                    : msg("hud.won-title", "team", event.result().winner()),
                    msg("hud.round-end-subtitle", "round", event.result().round()));
            sounds.match(Cue.ROUND_END, event.match());
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
                    sounds.match(Cue.ENEMY_SPAWN, match);
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
        Component round = msg(match.isExtraRound() ? "hud.extra-round-label" : "hud.round-label",
                "round", match.round());
        int duration = switch (match.phase()) {
            case PREP -> match.settings().prepSeconds();
            case COMBAT -> match.settings().roundSeconds();
            case OVERRUN -> match.settings().overrunLimitSeconds();
            case ROUND_END -> match.settings().roundEndSeconds();
            case SHOP -> match.isWaitingForMap() ? match.shopSettings().mapWaitLimitSeconds()
                    : match.shopSettings().maxSeconds();
            default -> 0;
        };
        Component label = switch (match.phase()) {
            case PREP -> msg("hud.bar-prep", "round", round, "time", clock(match.secondsLeft()));
            case COMBAT -> msg("hud.bar-combat", "round", round, "time", clock(match.secondsLeft()));
            case OVERRUN -> duration > 0
                    ? msg("hud.bar-overrun-timed", "round", round, "time", clock(match.secondsLeft()))
                    : msg("hud.bar-overrun", "round", round);
            case ROUND_END -> msg("hud.bar-round-end", "seconds", match.secondsLeft());
            case SHOP -> match.isWaitingForMap()
                    ? msg("hud.bar-shop-map-wait", "time", clock(match.secondsLeft()))
                    : msg("hud.bar-shop", "time", clock(match.secondsLeft()),
                            "ready", match.readyPlayers().size(), "players", match.players().size());
            default -> Component.empty();
        };
        display.bar.name(label);
        display.bar.color(switch (match.phase()) {
            case OVERRUN -> BossBar.Color.RED;
            case SHOP -> BossBar.Color.GREEN;
            default -> BossBar.Color.YELLOW;
        });
        display.bar.progress(duration == 0 ? 1 : Math.clamp((float) match.secondsLeft() / duration, 0, 1));
        for (UUID id : new ArrayList<>(display.viewers.keySet())) {
            Player player = players.apply(id);
            if (!match.contains(id) || player == null || !player.isOnline()) {
                removeViewer(display, id);
            }
        }
        List<String> totals = new ArrayList<>();
        for (int team : match.startingTeams()) {
            totals.add(text("hud.team-score", "team", team, "score", scores.score(match, team)));
        }
        int livingEnemies = Math.max(0, enemies.applyAsInt(match));
        each(match, player -> {
            if (display.viewers.putIfAbsent(player.getUniqueId(), player) == null) {
                player.showBossBar(display.bar);
            }
            player.sendActionBar(statusLine(totals, livingEnemies, match, player.getUniqueId()));
        });
    }

    private Component statusLine(List<String> totals, int livingEnemies, MatchView match, UUID player) {
        Component status = msg("hud.status", "totals", String.join(text("hud.team-separator"), totals),
                "enemies", livingEnemies, "points", points.points(match, player));
        List<Component> hints = new ArrayList<>();
        for (NinjaTool tool : List.of(NinjaTool.SHURIKEN, NinjaTool.SMOKE_BOMB)) {
            ThrowState state = tools.apply(player, tool);
            if (state.usedUp()) {
                hints.add(msg("hud.tool-used-up", "tool", msg("tool." + tool.key())));
            } else if (state.cooldownSeconds() > 0) {
                hints.add(msg("hud.tool-cooldown", "tool", msg("tool." + tool.key()),
                        "seconds", state.cooldownSeconds()));
            }
        }
        if (hints.isEmpty()) {
            return status;
        }
        return msg("hud.status-with-tools", "status", status,
                "tools", Component.join(JoinConfiguration.separator(
                        msg("hud.tool-separator")), hints));
    }

    private void title(MatchView match, Component title, Component subtitle) {
        Title message = Title.title(title, subtitle, TITLE_TIMES);
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
