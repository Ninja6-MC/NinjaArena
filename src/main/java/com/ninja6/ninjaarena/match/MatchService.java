package com.ninja6.ninjaarena.match;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.match.MatchResult.Reason;
import com.ninja6.ninjaarena.match.MatchUpdate.Closed;
import com.ninja6.ninjaarena.match.MatchUpdate.MatchEnded;
import com.ninja6.ninjaarena.match.MatchUpdate.PhaseChanged;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerEliminated;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerLeft;
import com.ninja6.ninjaarena.match.MatchUpdate.RoundEnded;
import com.ninja6.ninjaarena.match.MatchUpdate.RoundStarted;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.waiting.MatchStart;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs every match: takes over the players of a finished waiting-room countdown
 * ({@link MatchStart}), ticks each {@link Match} once a second, teleports teams to their
 * spawns at the start of every round, and sends everyone home through the restore flow when
 * the match closes. The round rules are in {@link Match}.
 *
 * <p><b>Arena.</b> A match holds its arena from start to close, so no other match, waiting
 * room or edit can use it. The arena is reset from its saved copy before the players arrive
 * and again when the match closes. If it cannot be held or the first reset fails, the
 * match does not start and its players are sent home; a failed reset at the close is logged,
 * and the registry keeps the arena unavailable until a reset succeeds.
 *
 * <p><b>Leaving.</b> A player leaves the match by the leave command, by being teleported out
 * of the arena world for any reason, or by disconnecting. Each of these restores them from
 * their snapshot, except a disconnect: that snapshot is restored when they next join. A
 * player is taken out of the match before the restore runs, so the restore's own teleport is
 * not seen as another exit. Disabling the plugin stops every match and sends everyone home.
 *
 * <p><b>Elimination (minimal).</b> Dying in a round takes the player out of that round; they
 * respawn at their team spawn and are back in the next round. Drops, spectating and how an
 * eliminated player waits for the next round are deliberately left to the death-handling
 * task, which builds on {@link PlayerEliminatedEvent} and {@link #respawnPoint(UUID)}.
 *
 * <p><b>Extension points.</b> Every change is announced as a {@link MatchEvent}; the read API
 * is {@link #matchOf(UUID)}, {@link #match(String)} and {@link #matches()}, each giving a
 * {@link MatchView}; {@link #setStandings(MatchStandings)} changes how teams are ranked.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class MatchService implements MatchStart {

    /** How a player left a match. */
    public enum Exit {
        /** The leave command; restored at once. */
        COMMAND,
        /** Teleported out of the arena world; restored on the next tick, once the teleport is done. */
        TELEPORTED,
        /** Disconnected; restored on their next join. */
        QUIT
    }

    private static final long TICKS_PER_SECOND = 20;

    /** A running match and the team spawns it places players at, team 1 first. */
    private record Running(Match match, List<ArenaPoint> spawns) {
    }

    private final Plugin plugin;
    private final ArenaRegistry arenas;
    private final Supplier<World> arenaWorld;
    private final MatchSettings settings;
    private final Function<Player, RestoreResult> restore;
    private final Consumer<? super MatchEvent> events;
    private final Consumer<Player> readyForRound;

    private MatchStandings standings = MatchStandings.ROUND_WINS;
    private final Map<String, Running> matches = new TreeMap<>();
    private final Map<UUID, Running> matchOf = new HashMap<>();
    /** Players out of their match whose restore waits for the next tick. */
    private final Set<UUID> homeBound = new HashSet<>();
    /** Matches with eliminations this server tick, waiting for {@link Match#settle()}. */
    private final Set<Running> unsettled = new HashSet<>();
    private BukkitTask ticker;

    /**
     * @param arenaWorld    {@code ArenaWorld#world}
     * @param restore       {@code RestoreService#restore}
     * @param events        calls each match event, {@code PluginManager#callEvent} on a server
     * @param readyForRound readies a living player for a round before they are placed at their
     *                      spawn; {@link #readyForRound(Player)} on a server
     */
    public MatchService(Plugin plugin, ArenaRegistry arenas, Supplier<World> arenaWorld, MatchSettings settings,
            Function<Player, RestoreResult> restore, Consumer<? super MatchEvent> events,
            Consumer<Player> readyForRound) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.arenas = Objects.requireNonNull(arenas, "arenas");
        this.arenaWorld = Objects.requireNonNull(arenaWorld, "arenaWorld");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.events = Objects.requireNonNull(events, "events");
        this.readyForRound = Objects.requireNonNull(readyForRound, "readyForRound");
    }

    /**
     * Puts a living player back to full health and food with no fire or fall distance, for the
     * start of a round.
     */
    public static void readyForRound(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            player.setHealth(maxHealth.getValue());
        }
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setExhaustion(0);
        player.setFireTicks(0);
        player.setFallDistance(0);
    }

    /** Starts ticking the matches once a second. */
    public void start() {
        if (ticker == null) {
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TICKS_PER_SECOND,
                    TICKS_PER_SECOND);
        }
    }

    /**
     * Changes how teams are ranked after the last round and in the final standings, for
     * matches started from now on. Team scoring sets one that ranks by points.
     */
    public void setStandings(MatchStandings standings) {
        this.standings = Objects.requireNonNull(standings, "standings");
    }

    /**
     * Starts a match with the players of a finished countdown: holds the arena, resets it,
     * and starts round 1, which teleports every team to its spawn. If the arena cannot be held
     * or reset, the players are told and sent home and nothing is thrown. Anything else that
     * goes wrong releases the arena and is thrown, so the waiting room sends the players home.
     */
    @Override
    public void start(String arenaName, List<List<UUID>> teams) {
        String name = Arena.normalizeName(arenaName);
        List<List<UUID>> online = new ArrayList<>();
        List<UUID> everyone = new ArrayList<>();
        for (List<UUID> team : teams) {
            List<UUID> members = new ArrayList<>();
            for (UUID playerId : team) {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null && player.isOnline() && !matchOf.containsKey(playerId)) {
                    members.add(playerId);
                    everyone.add(playerId);
                }
            }
            online.add(members);
        }
        if (everyone.isEmpty()) {
            plugin.getLogger().info("No players left to start a match in arena " + name + ".");
            return;
        }
        Optional<Arena> arena = arenas.get(name);
        if (arena.isEmpty() || arena.get().teamSpawns().size() < teams.size() || matches.containsKey(name)) {
            refuse(everyone, "Arena " + name + " is not available, so the match could not start.");
            plugin.getLogger().warning("Could not start a match in arena " + name + ": it is gone, has fewer team"
                    + " spawns than teams, or already has a match.");
            return;
        }
        try {
            arenas.hold(name);
        } catch (ArenaException e) {
            refuse(everyone, "Arena " + name + " is not available, so the match could not start.");
            plugin.getLogger().warning("Could not start a match in arena " + name + ": " + e.getMessage());
            return;
        }
        try {
            arenas.reset(name);
        } catch (ArenaException e) {
            arenas.release(name);
            refuse(everyone, "Arena " + name + " could not be prepared, so the match could not start.");
            plugin.getLogger().log(Level.SEVERE, "Could not reset arena " + name + " before its match; it stays"
                    + " unavailable until a reset succeeds or an edit finishes.", e);
            return;
        }
        Running running = null;
        try {
            running = new Running(new Match(name, online, settings, standings), arena.get().teamSpawns());
            matches.put(name, running);
            for (UUID playerId : everyone) {
                matchOf.put(playerId, running);
            }
            plugin.getLogger().info("Match started in arena " + name + " with teams " + online + ".");
            apply(running, running.match().begin());
        } catch (RuntimeException e) {
            if (running != null) {
                forget(running);
            }
            arenas.release(name);
            throw e;
        }
    }

    /** The match the player is in, if any. */
    public Optional<MatchView> matchOf(UUID playerId) {
        return Optional.ofNullable(matchOf.get(playerId)).map(Running::match);
    }

    /** The match running in an arena, if any. */
    public Optional<MatchView> match(String arenaName) {
        return Optional.ofNullable(matches.get(Arena.normalizeName(arenaName))).map(Running::match);
    }

    /** Every running match, by arena name. */
    public List<MatchView> matches() {
        return matches.values().stream().map(running -> (MatchView) running.match()).toList();
    }

    public boolean isInMatch(UUID playerId) {
        return matchOf.containsKey(playerId);
    }

    /**
     * Whether a player in a match should take no damage now: in any phase but COMBAT and
     * OVERRUN. False for a player not in a match.
     */
    public boolean isSheltered(UUID playerId) {
        Running running = matchOf.get(playerId);
        return running != null && !running.match().phase().isFighting();
    }

    /**
     * Whether a player in a match may deal no damage now: they are out of the current round.
     * A stopgap until the death-handling task moves eliminated players to spectating, so a
     * player who is out cannot decide the round. False for a player not in a match.
     */
    public boolean isDisarmed(UUID playerId) {
        Running running = matchOf.get(playerId);
        return running != null && !running.match().isAlive(playerId);
    }

    /** Where a player in a match respawns: their team spawn. Empty for a player not in a match. */
    public Optional<Location> respawnPoint(UUID playerId) {
        Running running = matchOf.get(playerId);
        if (running == null) {
            return Optional.empty();
        }
        int team = running.match().teamOf(playerId);
        return Optional.of(location(running.spawns().get(team - 1)));
    }

    /**
     * A player in a match died. Takes them out of the current round, if they are alive in it.
     *
     * @param killer whoever killed them, or {@code null}; credited only if in the same match
     */
    public void died(Player player, Player killer) {
        Running running = matchOf.get(player.getUniqueId());
        if (running == null) {
            return;
        }
        UUID killerId = killer != null && matchOf.get(killer.getUniqueId()) == running ? killer.getUniqueId() : null;
        apply(running, running.match().eliminate(player.getUniqueId(), killerId));
        settleLater(running);
    }

    /**
     * Takes a player out of their match and, unless they quit, sends them home.
     *
     * @return false if they were not in a match
     */
    public boolean leave(Player player, Exit exit) {
        UUID playerId = player.getUniqueId();
        Running running = matchOf.remove(playerId);
        if (running == null) {
            return false;
        }
        apply(running, running.match().leave(playerId));
        settleLater(running);
        switch (exit) {
            case QUIT -> {
                // The restore listener marks the snapshot; it is restored on the next join.
            }
            case COMMAND -> {
                player.sendMessage(Component.text("You left the match.", NamedTextColor.YELLOW));
                sendHome(player);
            }
            case TELEPORTED -> {
                player.sendMessage(Component.text("You left the match.", NamedTextColor.YELLOW));
                // Not inside the teleport event that saw the player leave.
                homeBound.add(playerId);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!homeBound.remove(playerId)) {
                        return;
                    }
                    Player online = plugin.getServer().getPlayer(playerId);
                    if (online != null && online.isOnline()) {
                        sendHome(online);
                    }
                });
            }
        }
        return true;
    }

    /** Advances every match by a second. Run once a second by {@link #start()}. */
    public void tick() {
        for (Running running : new ArrayList<>(matches.values())) {
            apply(running, running.match().tick());
            if (!running.match().isClosed()) {
                showStatus(running);
            }
        }
    }

    /** Stops every match and sends every player home. Called on plugin disable. */
    public void close() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Running running : new ArrayList<>(matches.values())) {
            apply(running, running.match().stop());
        }
        // Restores put off to a tick that will now never run happen here too.
        List<UUID> pending = new ArrayList<>(homeBound);
        homeBound.clear();
        for (UUID playerId : pending) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                sendHome(player);
            }
        }
    }

    /**
     * Settles the match on the next server tick, once every death and departure of this one
     * is in, so players who go down together draw the round instead of the first to fall
     * losing it.
     */
    private void settleLater(Running running) {
        if (running.match().isClosed() || !unsettled.add(running)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (unsettled.remove(running) && matches.get(running.match().arena()) == running) {
                apply(running, running.match().settle());
            }
        });
    }

    private void apply(Running running, List<MatchUpdate> updates) {
        Match match = running.match();
        for (MatchUpdate update : updates) {
            switch (update) {
                case PhaseChanged changed -> {
                    if (changed.to() == MatchPhase.PREP) {
                        place(running);
                    }
                    announce(running, changed.to());
                    events.accept(new MatchPhaseChangeEvent(match, changed.from(), changed.to()));
                }
                case RoundStarted started -> events.accept(new RoundStartEvent(match, started.round(),
                        started.extra()));
                case PlayerEliminated out -> {
                    if (out.cause() == MatchUpdate.Cause.DIED) {
                        broadcast(running, Component.text(name(out.player()) + " is out for this round.",
                                NamedTextColor.GRAY));
                    }
                    events.accept(new PlayerEliminatedEvent(match, out.player(), out.team(), out.cause(),
                            out.killer()));
                }
                case PlayerLeft left -> {
                    broadcast(running, Component.text(name(left.player()) + " left the match.", NamedTextColor.GRAY));
                    events.accept(new PlayerLeaveMatchEvent(match, left.player(), left.team()));
                }
                case RoundEnded ended -> {
                    RoundResult result = ended.result();
                    broadcast(running, result.isDraw()
                            ? Component.text("Round " + result.round() + " is a draw.", NamedTextColor.YELLOW)
                            : Component.text("Team " + result.winner() + " wins round " + result.round() + ".",
                                    NamedTextColor.GOLD));
                    events.accept(new RoundEndEvent(match, result));
                }
                case MatchEnded ended -> {
                    announceResult(running, ended.result());
                    plugin.getLogger().info("Match in arena " + match.arena() + " ended: " + ended.result().reason()
                            + ", winners " + ended.result().winners() + ".");
                    // Listeners show their results (the score table) between the winner and the countdown.
                    events.accept(new MatchEndEvent(match, ended.result()));
                    announceReturn(running, ended.result());
                }
                case Closed closed -> close(running);
            }
        }
    }

    /** Readies every player in the match and teleports them to their team spawn. */
    private void place(Running running) {
        Match match = running.match();
        for (UUID playerId : match.players()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (player.isDead()) {
                // Placed at the team spawn by the respawn.
                continue;
            }
            readyForRound.accept(player);
            Location spawn = location(running.spawns().get(match.teamOf(playerId) - 1));
            if (!player.teleport(spawn)) {
                plugin.getLogger().warning("Could not teleport " + player.getName() + " to their team spawn in arena "
                        + match.arena() + ".");
            }
        }
    }

    /** Sends everyone still in the match home, resets the arena and releases it. */
    private void close(Running running) {
        Match match = running.match();
        if (!matches.remove(match.arena(), running)) {
            return;
        }
        List<UUID> remaining = match.players();
        forget(running);
        unsettled.remove(running);
        for (UUID playerId : remaining) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendActionBar(Component.empty());
                sendHome(player);
            }
        }
        try {
            arenas.reset(match.arena());
        } catch (ArenaException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not reset arena " + match.arena() + " after its match; it"
                    + " stays unavailable until a reset succeeds or an edit finishes.", e);
        }
        arenas.release(match.arena());
    }

    private void forget(Running running) {
        matches.remove(running.match().arena(), running);
        matchOf.values().removeIf(value -> value == running);
    }

    private void refuse(List<UUID> players, String message) {
        for (UUID playerId : players) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(message, NamedTextColor.RED));
                sendHome(player);
            }
        }
    }

    private void announce(Running running, MatchPhase phase) {
        Match match = running.match();
        switch (phase) {
            case PREP -> broadcast(running, Component.text((match.isExtraRound() ? "Extra round! "
                    : "Round " + match.round() + " of " + settings.rounds() + ". ") + "The fight starts in "
                    + match.secondsLeft() + " seconds.", NamedTextColor.GOLD));
            case COMBAT -> broadcast(running, Component.text("Fight!", NamedTextColor.RED));
            case OVERRUN -> broadcast(running, Component.text("Overrun! Time is up: the round goes on until one"
                    + " team is left" + (settings.overrunLimitSeconds() > 0 ? ", for at most "
                    + settings.overrunLimitSeconds() + " seconds." : "."), NamedTextColor.DARK_RED));
            case WAITING, ROUND_END, MATCH_END -> {
                // Round and match results are announced with their own update.
            }
        }
    }

    private void announceResult(Running running, MatchResult result) {
        Component headline = switch (result.reason()) {
            case COMPLETED -> result.isSharedWin()
                    ? Component.text("Teams " + join(result.winners()) + " share the win!", NamedTextColor.GOLD)
                    : Component.text("Team " + result.winners().getFirst() + " wins the match!", NamedTextColor.GOLD);
            case FORFEIT -> Component.text("Team " + join(result.winners()) + " wins the match: no other team is"
                    + " left.", NamedTextColor.GOLD);
            case STOPPED -> Component.text("The match was stopped.", NamedTextColor.RED);
            // Nobody is left to tell.
            case ABANDONED -> null;
        };
        if (headline != null) {
            broadcast(running, headline);
        }
    }

    private void announceReturn(Running running, MatchResult result) {
        if (result.reason() != Reason.STOPPED && result.reason() != Reason.ABANDONED) {
            broadcast(running, Component.text("Returning in " + settings.matchEndSeconds() + " seconds.",
                    NamedTextColor.GRAY));
        }
    }

    private void showStatus(Running running) {
        Match match = running.match();
        String round = match.isExtraRound() ? "Extra round" : "Round " + match.round() + "/" + settings.rounds();
        Component line = switch (match.phase()) {
            case WAITING -> Component.empty();
            case PREP -> Component.text(round + " - Prep " + match.secondsLeft() + "s", NamedTextColor.GOLD);
            case COMBAT -> Component.text(round + " - " + clock(match.secondsLeft()) + " - " + alive(match),
                    NamedTextColor.YELLOW);
            case OVERRUN -> Component.text(round + " - OVERRUN - " + alive(match), NamedTextColor.DARK_RED);
            case ROUND_END -> Component.text("Next round in " + match.secondsLeft() + "s", NamedTextColor.GRAY);
            case MATCH_END -> Component.text("Returning in " + match.secondsLeft() + "s", NamedTextColor.GRAY);
        };
        for (UUID playerId : match.players()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.sendActionBar(line);
            }
        }
    }

    private static String alive(Match match) {
        List<String> teams = new ArrayList<>();
        for (int team : match.startingTeams()) {
            long count = match.alivePlayers().stream().filter(player -> match.teamOf(player) == team).count();
            teams.add("T" + team + ": " + count);
        }
        return String.join("  ", teams);
    }

    private static String clock(int seconds) {
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    private static String join(List<Integer> teams) {
        return teams.stream().map(String::valueOf).collect(Collectors.joining(" and "));
    }

    private Location location(ArenaPoint point) {
        return new Location(arenaWorld.get(), point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    private String name(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        return player != null ? player.getName() : playerId.toString();
    }

    private void sendHome(Player player) {
        RestoreResult result = restore.apply(player);
        if (result == RestoreResult.FAILED) {
            player.sendMessage(Component.text("Your belongings could not be returned yet. Rejoin the server,"
                    + " or ask an admin if this keeps happening.", NamedTextColor.RED));
        }
    }

    private void broadcast(Running running, Component message) {
        for (UUID playerId : running.match().players()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }
}
