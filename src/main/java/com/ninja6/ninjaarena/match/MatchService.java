package com.ninja6.ninjaarena.match;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.MapSource;
import com.ninja6.ninjaarena.event.EventSettings.Bonus;
import com.ninja6.ninjaarena.kit.KitInventory;
import com.ninja6.ninjaarena.match.MatchResult.Reason;
import com.ninja6.ninjaarena.match.MatchUpdate.Closed;
import com.ninja6.ninjaarena.match.MatchUpdate.MapReplay;
import com.ninja6.ninjaarena.match.MatchUpdate.MapWait;
import com.ninja6.ninjaarena.match.MatchUpdate.MatchEnded;
import com.ninja6.ninjaarena.match.MatchUpdate.PhaseChanged;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerDisconnected;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerEliminated;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerLeft;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerReady;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerRejoined;
import com.ninja6.ninjaarena.match.MatchUpdate.QuickStart;
import com.ninja6.ninjaarena.match.MatchUpdate.RoundEnded;
import com.ninja6.ninjaarena.match.MatchUpdate.RoundStarted;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.PlayerRejoinMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.sound.Sounds;
import com.ninja6.ninjaarena.sound.Sounds.Cue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs every match: takes over the players of a room its host or an admin has started
 * ({@link #start(String, List)}), ticks each {@link Match} once a second, teleports teams to their
 * spawns at the start of every round, and sends everyone to the lobby when the match closes
 * ({@link #setLobby}). The round rules are in {@link Match}.
 *
 * <p><b>Arena.</b> A match holds its arena from start to close, so no other match or edit
 * can use it. The arena is reset from its saved copy before the players arrive
 * and again when the match closes. If it cannot be held or the first reset fails, the
 * match does not start and its players go back; a failed reset at the close is logged,
 * and the registry keeps the arena unavailable until a reset succeeds. Arenas are found
 * through a {@link MapSource}, so a generated map in a slot cell plays the same way; its
 * room clears and regenerates the cell after the match instead of restoring a copy. The
 * team spawns are read from the map again at every round's PREP.
 *
 * <p><b>Leaving.</b> A player leaves the match by the leave command, by being teleported out
 * of the arena world for any reason, or by disconnecting. The leave command, like the end of
 * the match, takes them to the lobby with their snapshot kept; a teleport out restores them
 * home from it; a disconnect leaves it for their next join. Where the lobby cannot take a
 * player, they are sent home. A player is taken out of the match before they are moved, so
 * that teleport is not seen as another exit. Disabling the plugin stops every match and
 * sends everyone home.
 *
 * <p><b>Rejoining.</b> With a rejoin grace period set ({@link #setRejoinGraceSeconds}), a
 * player who disconnects before the match ends is {@link #disconnect held} instead: still in
 * the match, on their team and in their room, out of the round (see {@link Match}), with
 * their kit cleared as on any exit and their snapshot, points and shop purchases kept.
 * Joining again within the grace ({@link #rejoin}) puts them back: alive at their team spawn
 * during PREP, in the shop area during SHOP, otherwise spectating their team until the next
 * round. When the grace runs out, or the plugin is disabled, they leave the match as a
 * disconnect always did; when the match ends or closes first, they are no longer held.
 * Either way their next join takes them to the lobby, or home, as before. A grace of 0
 * keeps a disconnect a leave.
 *
 * <p><b>Shop.</b> When a match goes to SHOP ({@link ShopSettings}), every player still in it,
 * eliminated players included, is put back in Adventure mode and moved to the lobby world's
 * shop area ({@link #setShopArea}); a player on the death screen respawns there. They stay
 * in the match, keep their inventory and snapshot, and take no damage. The next PREP brings
 * them back to their team spawns. Those moves cross between the plugin's two worlds and
 * keep the match only while {@link #crossesWorlds} accepts the player. {@link #ready} is
 * the Ready button, and {@link #setMapReady} keeps the shop open until the next map is ready.
 *
 * <p><b>Elimination.</b> Dying in a round takes the player out of that round. Death handling
 * builds on {@link PlayerEliminatedEvent} and {@link #respawnPoint(UUID)} to retain their
 * belongings and spectate their team until the next round.
 *
 * <p><b>Extension points.</b> Every change is announced as a {@link MatchEvent}; the read API
 * is {@link #matchOf(UUID)}, {@link #match(String)} and {@link #matches()}, each giving a
 * {@link MatchView}; {@link #setStandings(MatchStandings)} changes how teams are ranked.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class MatchService {

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

    /** The longest rejoin grace period, in seconds. */
    public static final int MAX_REJOIN_GRACE_SECONDS = 600;

    /**
     * A running match and the team spawns it places players at, team 1 first. The spawns
     * are read again at every round's PREP, so a map that changes between rounds can move
     * them. Compared by identity.
     */
    private static final class Running {

        private final Match match;
        private List<ArenaPoint> spawns;

        Running(Match match, List<ArenaPoint> spawns) {
            this.match = match;
            this.spawns = List.copyOf(spawns);
        }

        Match match() {
            return match;
        }

        List<ArenaPoint> spawns() {
            return spawns;
        }
    }

    /** What {@link #ready} did. */
    public enum Ready {
        /** The player is now ready. */
        READY,
        /** The player was ready already. */
        ALREADY_READY,
        /** The player is in no match, or their match is not in SHOP. */
        NOT_IN_SHOP
    }

    private Function<String, Bonus> eventBonus = arena -> Bonus.NONE;
    private Predicate<Player> lobby = player -> false;
    private Consumer<String> closed = arena -> { };
    private boolean closing;
    private ShopSettings shopSettings = ShopSettings.OFF;
    private Predicate<MatchView> mapReady = match -> true;
    private Supplier<Location> shopArea = () -> null;

    /**
     * Where players go when the match ends or they leave it with the command: the lobby,
     * {@code RoomService#enterLobby} on a server. When it answers false they are sent home
     * from their snapshot; by default everyone is.
     */
    public void setLobby(Predicate<Player> lobby) {
        this.lobby = Objects.requireNonNull(lobby, "lobby");
    }

    /**
     * Told an arena's name once its match has closed, its players have gone back and the
     * arena is reset and released.
     */
    public void onClose(Consumer<String> closed) {
        Consumer<String> earlier = this.closed;
        Objects.requireNonNull(closed, "closed");
        this.closed = arena -> {
            earlier.accept(arena);
            closed.accept(arena);
        };
    }

    /**
     * The shop phase for matches started from now on; a running match keeps its own. Off
     * until set, so each round end leads straight to the next round.
     */
    public void setShopSettings(ShopSettings shopSettings) {
        this.shopSettings = Objects.requireNonNull(shopSettings, "shopSettings");
    }

    public ShopSettings shopSettings() {
        return shopSettings;
    }

    /**
     * Where players stand during SHOP: the lobby world's shop area on a server. While it gives
     * {@code null}, the default, they stay where the round left them.
     */
    public void setShopArea(Supplier<Location> shopArea) {
        this.shopArea = Objects.requireNonNull(shopArea, "shopArea");
    }

    /**
     * Whether the map for a match's next round is ready, asked once a SHOP is due to end.
     * Nothing prepares another map yet, so the default is always ready and every round is
     * played on the match's own arena.
     */
    public void setMapReady(Predicate<MatchView> mapReady) {
        this.mapReady = Objects.requireNonNull(mapReady, "mapReady");
    }

    public void setEventBonus(Function<String, Bonus> eventBonus) {
        this.eventBonus = Objects.requireNonNull(eventBonus, "eventBonus");
    }

    private final Plugin plugin;
    private final MapSource arenas;
    private final Supplier<World> arenaWorld;
    private MatchSettings settings;
    private Sounds sounds;
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
    /** Matches whose players are being brought back from the shop to their team spawns. */
    private final Set<Running> returning = new HashSet<>();
    /** Disconnected players held in their match, with the seconds of grace left. */
    private final Map<UUID, Integer> held = new HashMap<>();
    /** Held players' names, for the messages about them sent while they are offline. */
    private final Map<UUID, String> heldNames = new HashMap<>();
    /** Rejoined players being placed, whose moves between the worlds keep the match. */
    private final Set<UUID> placing = new HashSet<>();
    private int rejoinGraceSeconds;
    private BukkitTask ticker;

    /**
     * @param arenaWorld    {@code ArenaWorld#world}
     * @param restore       {@code RestoreService#restore}
     * @param events        calls each match event, {@code PluginManager#callEvent} on a server
     * @param readyForRound readies a living player for a round before they are placed at their
     *                      spawn; {@link #readyForRound(Player)} on a server
     */
    public MatchService(Plugin plugin, MapSource arenas, Supplier<World> arenaWorld, MatchSettings settings,
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

    /**
     * How long a player who disconnects from a match is held in it, in seconds, clamped to 0
     * to {@link #MAX_REJOIN_GRACE_SECONDS}. Applies to disconnects from now on; players held
     * already keep the grace they were given. 0, the default, makes a disconnect a leave.
     */
    public void setRejoinGraceSeconds(int seconds) {
        rejoinGraceSeconds = Math.clamp(seconds, 0, MAX_REJOIN_GRACE_SECONDS);
    }

    public int rejoinGraceSeconds() {
        return rejoinGraceSeconds;
    }

    /** Whether the player disconnected from their match and is held in it, within the grace. */
    public boolean isHeld(UUID playerId) {
        return held.containsKey(playerId);
    }

    /** Changes the defaults for the next match; existing matches retain their settings. */
    public void setSettings(MatchSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
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
     * Starts a match with the players of a room its host or an admin started: holds the
     * arena, resets it, and starts round 1, which teleports every team to its spawn. If the
     * arena cannot be held or reset, the players are told and sent to the lobby, or home, and
     * nothing is thrown. Anything else that goes wrong releases the arena and is thrown, so the
     * caller can deal with its players.
     */
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
            refuse(everyone, msg("match.arena-unavailable", "arena", name));
            plugin.getLogger().warning("Could not start a match in arena " + name + ": it is gone, has fewer team"
                    + " spawns than teams, or already has a match.");
            return;
        }
        try {
            arenas.hold(name);
        } catch (ArenaException e) {
            refuse(everyone, msg("match.arena-unavailable", "arena", name));
            plugin.getLogger().warning("Could not start a match in arena " + name + ": " + e.getMessage());
            return;
        }
        try {
            arenas.reset(name);
        } catch (ArenaException e) {
            arenas.release(name);
            refuse(everyone, msg("match.arena-not-prepared", "arena", name));
            plugin.getLogger().log(Level.SEVERE, "Could not reset arena " + name + " before its match; it stays"
                    + " unavailable until a reset succeeds or an edit finishes.", e);
            return;
        }
        Running running = null;
        try {
            Match match = new Match(name, online, settings, standings, eventBonus.apply(name), shopSettings);
            match.setMapReady(() -> mapReady.test(match));
            match.setMapSeed(() -> arenas.seed(name));
            running = new Running(match, arena.get().teamSpawns());
            matches.put(name, running);
            for (UUID playerId : everyone) {
                matchOf.put(playerId, running);
            }
            plugin.getLogger().info("Match started in arena " + name + " with teams " + online + ".");
            apply(running, running.match().begin());
            if (sounds != null) {
                sounds.match(Cue.MATCH_START, running.match());
            }
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
     * A safety guard alongside spectator mode, including delayed respawns, so a player who
     * is out cannot decide the round. False for a player not in a match.
     */
    public boolean isDisarmed(UUID playerId) {
        Running running = matchOf.get(playerId);
        return running != null && !running.match().isAlive(playerId);
    }

    /**
     * Where a player in a match respawns: their team spawn, or the shop area during SHOP.
     * Empty for a player not in a match.
     */
    public Optional<Location> respawnPoint(UUID playerId) {
        Running running = matchOf.get(playerId);
        if (running == null) {
            return Optional.empty();
        }
        if (running.match().phase() == MatchPhase.SHOP) {
            Location shop = shopArea.get();
            if (shop != null) {
                return Optional.of(shop.clone());
            }
        }
        int team = running.match().teamOf(playerId);
        return Optional.of(location(running.spawns().get(team - 1)));
    }

    /**
     * Whether a player in a match keeps it when NinjaArena moves them between the arena and
     * lobby worlds: during SHOP, and while the next PREP brings them back. Register it with
     * {@code CrossWorldTransfers#allow}.
     */
    public boolean crossesWorlds(UUID playerId) {
        Running running = matchOf.get(playerId);
        return running != null && (running.match().phase() == MatchPhase.SHOP || returning.contains(running)
                || placing.contains(playerId));
    }

    public void setSounds(Sounds sounds) {
        this.sounds = sounds;
    }

    /**
     * The player pressed Ready in the shop. Once everyone in the match is ready, the shop's
     * time left is cut to its quick start.
     */
    public Ready ready(Player player) {
        Running running = matchOf.get(player.getUniqueId());
        if (running == null || running.match().phase() != MatchPhase.SHOP) {
            return Ready.NOT_IN_SHOP;
        }
        List<MatchUpdate> updates = running.match().ready(player.getUniqueId());
        if (updates.isEmpty()) {
            return Ready.ALREADY_READY;
        }
        apply(running, updates);
        if (sounds != null) {
            sounds.match(Cue.READY, running.match());
        }
        return Ready.READY;
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
     * Takes a player out of their match. The command takes them to the lobby, a teleport
     * sends them home, and a quit leaves them for their next join.
     *
     * @return false if they were not in a match
     */
    public boolean leave(Player player, Exit exit) {
        UUID playerId = player.getUniqueId();
        held.remove(playerId);
        heldNames.remove(playerId);
        Running running = matchOf.remove(playerId);
        if (running == null) {
            return false;
        }
        releasePlayer(player);
        apply(running, running.match().leave(playerId));
        settleLater(running);
        switch (exit) {
            case QUIT -> {
                // The restore listener marks the snapshot; it is restored on the next join.
            }
            case COMMAND -> {
                player.sendMessage(msg("match.left"));
                sendBack(player);
            }
            case TELEPORTED -> {
                player.sendMessage(msg("match.left"));
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

    /**
     * A player in a match disconnected. With a rejoin grace period, and before the match has
     * ended, they are held in it: out of the round, their kit cleared, everything else kept
     * until they {@link #rejoin} or the grace runs out. Otherwise they leave it as with
     * {@link Exit#QUIT}.
     *
     * @return false if they were not in a match
     */
    public boolean disconnect(Player player) {
        UUID playerId = player.getUniqueId();
        Running running = matchOf.get(playerId);
        if (running == null) {
            return false;
        }
        Match match = running.match();
        if (rejoinGraceSeconds == 0 || held.containsKey(playerId) || match.isClosed()
                || match.phase() == MatchPhase.MATCH_END) {
            return leave(player, Exit.QUIT);
        }
        held.put(playerId, rejoinGraceSeconds);
        heldNames.put(playerId, player.getName());
        // Out of the round first, so they no longer count as spectating when their mode is reset.
        List<MatchUpdate> updates = match.disconnect(playerId);
        releasePlayer(player);
        plugin.getLogger().info(player.getName() + " disconnected from the match in arena " + match.arena()
                + "; they have " + rejoinGraceSeconds + " seconds to rejoin.");
        apply(running, updates);
        settleLater(running);
        return true;
    }

    /**
     * A player joined the server. If they are held in a match, they are back in it, and placed
     * on the next tick, once the join has finished. A match that has already ended lets them
     * go instead, and their join is handled as after any disconnect.
     *
     * @return whether they are back in a match
     */
    public boolean rejoin(Player player) {
        UUID playerId = player.getUniqueId();
        if (!held.containsKey(playerId)) {
            return false;
        }
        Running running = matchOf.get(playerId);
        if (running == null || running.match().isClosed() || running.match().phase() == MatchPhase.MATCH_END) {
            expire(playerId);
            return false;
        }
        held.remove(playerId);
        heldNames.remove(playerId);
        plugin.getLogger().info(player.getName() + " rejoined the match in arena " + running.match().arena() + ".");
        apply(running, running.match().reconnect(playerId));
        plugin.getServer().getScheduler().runTask(plugin, () -> placeRejoined(playerId, running));
        return true;
    }

    /** The grace of a held player ran out, or their match no longer takes them: they leave it. */
    private void expire(UUID playerId) {
        if (held.remove(playerId) == null) {
            return;
        }
        Running running = matchOf.remove(playerId);
        if (running != null) {
            plugin.getLogger().info(name(playerId) + " did not rejoin the match in arena " + running.match().arena()
                    + " in time and left it.");
            apply(running, running.match().leave(playerId));
            settleLater(running);
        }
        heldNames.remove(playerId);
    }

    /** Counts down every held player's grace by a second. */
    private void tickHolds() {
        for (UUID playerId : new ArrayList<>(held.keySet())) {
            Integer left = held.computeIfPresent(playerId, (id, seconds) -> seconds - 1);
            if (left != null && left <= 0) {
                expire(playerId);
            }
        }
    }

    /**
     * Places a rejoined player, once their join has finished, by the phase the match is in
     * then, which may have moved on since they joined: during PREP at their team spawn, alive
     * in the round from now; during SHOP in the shop area; in any other phase where they are,
     * for death handling to make them spectate until the next round. A player the round start
     * has already placed in a round keeps their place. A player on the death screen is placed
     * by their respawn, which reads the same state.
     */
    private void placeRejoined(UUID playerId, Running running) {
        Player player = plugin.getServer().getPlayer(playerId);
        Match match = running.match();
        if (player == null || !player.isOnline() || matchOf.get(playerId) != running || held.containsKey(playerId)
                || match.isClosed()) {
            return;
        }
        placing.add(playerId);
        try {
            if (match.phase() == MatchPhase.PREP) {
                match.revive(playerId);
            }
            boolean alive = match.isAlive(playerId);
            if (!player.isDead()) {
                if (alive && match.phase() == MatchPhase.PREP) {
                    toAdventure(player);
                    readyForRound.accept(player);
                    teleport(player, location(running.spawns().get(match.teamOf(playerId) - 1)), "their team spawn");
                } else if (match.phase() == MatchPhase.SHOP) {
                    toAdventure(player);
                    Location shop = shopArea.get();
                    if (shop != null) {
                        teleport(player, shop.clone(), "the shop area");
                    }
                }
            }
            player.sendMessage(msg(alive ? "match.rejoined"
                    : match.phase() == MatchPhase.SHOP ? "match.rejoined-shop" : "match.rejoined-spectating"));
            events.accept(new PlayerRejoinMatchEvent(match, playerId, match.teamOf(playerId)));
        } finally {
            placing.remove(playerId);
        }
    }

    private void teleport(Player player, Location to, String where) {
        if (!OwnTeleports.teleport(player, to, TeleportCause.PLUGIN)) {
            plugin.getLogger().warning("Could not teleport " + player.getName() + " to " + where + " after they"
                    + " rejoined their match.");
        }
    }

    private static void toAdventure(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            if (player.getSpectatorTarget() != null) {
                player.setSpectatorTarget(null);
            }
            player.setGameMode(GameMode.ADVENTURE);
        }
        player.setFireTicks(0);
        player.setFallDistance(0);
    }

    /** Advances every match by a second. Run once a second by {@link #start()}. */
    public void tick() {
        tickHolds();
        for (Running running : new ArrayList<>(matches.values())) {
            apply(running, running.match().tick());
        }
    }

    /** Stops every match and sends every player home. Called on plugin disable. */
    public void close() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        closing = true;
        // Disconnected players held in a match leave it, as a disconnect without a grace does.
        for (UUID playerId : new ArrayList<>(held.keySet())) {
            expire(playerId);
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
                        if (changed.from() == MatchPhase.SHOP) {
                            returning.add(running);
                        }
                        try {
                            place(running);
                        } finally {
                            returning.remove(running);
                        }
                    } else if (changed.to() == MatchPhase.SHOP) {
                        toShop(running);
                    }
                    announce(running, changed.to());
                    events.accept(new MatchPhaseChangeEvent(match, changed.from(), changed.to()));
                }
                case RoundStarted started -> events.accept(new RoundStartEvent(match, started.round(),
                        started.extra()));
                case PlayerEliminated out -> {
                    if (out.cause() == MatchUpdate.Cause.DIED) {
                        broadcast(running, msg("match.player-out", "player", name(out.player())));
                    }
                    events.accept(new PlayerEliminatedEvent(match, out.player(), out.team(), out.cause(),
                            out.killer()));
                }
                case PlayerLeft left -> {
                    broadcast(running, msg("match.player-left", "player", name(left.player())));
                    events.accept(new PlayerLeaveMatchEvent(match, left.player(), left.team()));
                }
                case PlayerDisconnected away -> broadcast(running, msg("match.player-disconnected",
                        "player", name(away.player()), "seconds", held.getOrDefault(away.player(), 0)),
                        away.player());
                case PlayerRejoined back -> broadcast(running, msg("match.player-rejoined",
                        "player", name(back.player())), back.player());
                case PlayerReady ready -> broadcast(running, msg("shop.player-ready", "player",
                        name(ready.player()), "ready", ready.ready(), "players", ready.players()));
                case QuickStart quick -> broadcast(running, msg("shop.quick-start", "seconds", quick.seconds()));
                case MapWait wait -> broadcast(running, msg("shop.map-wait", "seconds", wait.seconds()));
                case MapReplay replay -> replay(running);
                case RoundEnded ended -> {
                    RoundResult result = ended.result();
                    broadcast(running, result.isDraw()
                            ? msg("match.round-draw", "round", result.round())
                            : msg("match.round-won", "team", result.winner(), "round", result.round()));
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

    /**
     * Reads the match's team spawns from its map again, for a new round: a generated map
     * placed again between rounds can have moved them. A map that is gone or now has fewer
     * spawns than the match has teams keeps the spawns the match already has.
     */
    private void refreshSpawns(Running running) {
        Match match = running.match();
        Optional<List<ArenaPoint>> spawns = arenas.get(match.arena()).map(Arena::teamSpawns);
        if (spawns.isPresent() && spawns.get().size() >= match.teamCount()) {
            running.spawns = List.copyOf(spawns.get());
        } else {
            plugin.getLogger().warning("Arena " + match.arena() + " has no team spawn for every team now; the match"
                    + " keeps its earlier spawns.");
        }
    }

    /** Readies every player in the match and teleports them to their team spawn. */
    private void place(Running running) {
        refreshSpawns(running);
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
            if (!OwnTeleports.teleport(player, spawn, TeleportCause.PLUGIN)) {
                plugin.getLogger().warning("Could not teleport " + player.getName() + " to their team spawn in arena "
                        + match.arena() + ".");
            }
        }
    }

    /**
     * Moves every player in the match to the shop area, out of spectator mode. A player on
     * the death screen is placed there by their respawn; one the teleport does not move stays
     * where they are, still in the match.
     */
    private void toShop(Running running) {
        Location shop = shopArea.get();
        if (shop == null) {
            return;
        }
        Match match = running.match();
        for (UUID playerId : match.players()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            if (player.getGameMode() == GameMode.SPECTATOR) {
                if (player.getSpectatorTarget() != null) {
                    player.setSpectatorTarget(null);
                }
                player.setGameMode(GameMode.ADVENTURE);
            }
            player.setFireTicks(0);
            player.setFallDistance(0);
            if (!OwnTeleports.teleport(player, shop.clone(), TeleportCause.PLUGIN)) {
                plugin.getLogger().warning("Could not teleport " + player.getName() + " to the shop area for the match"
                        + " in arena " + match.arena() + ".");
            }
        }
    }

    /** The next map was not ready in time: the round is played on the current arena, reset first. */
    private void replay(Running running) {
        String arena = running.match().arena();
        plugin.getLogger().info("The next map for the match in arena " + arena + " was not ready in time; replaying"
                + " on arena " + arena + " after a reset.");
        broadcast(running, msg("shop.map-replay", "arena", arena));
        try {
            arenas.reset(arena);
        } catch (ArenaException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not reset arena " + arena + " before the replayed round; the"
                    + " round is played on it as it is.", e);
        }
    }

    /**
     * Sends everyone still in the match to the lobby, or home on plugin disable, resets the
     * arena and releases it.
     */
    private void close(Running running) {
        Match match = running.match();
        if (!matches.remove(match.arena(), running)) {
            return;
        }
        List<UUID> remaining = match.players();
        forget(running);
        unsettled.remove(running);
        returning.remove(running);
        for (UUID playerId : remaining) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendActionBar(Component.empty());
                if (closing) {
                    sendHome(player);
                } else {
                    sendBack(player);
                }
            }
        }
        try {
            arenas.reset(match.arena());
        } catch (ArenaException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not reset arena " + match.arena() + " after its match; it"
                    + " stays unavailable until a reset succeeds or an edit finishes.", e);
        }
        arenas.release(match.arena());
        closed.accept(match.arena());
    }

    private void forget(Running running) {
        // Held players are let go with their match; their next join is handled as after any quit.
        held.keySet().removeIf(id -> matchOf.get(id) == running);
        heldNames.keySet().retainAll(held.keySet());
        matches.remove(running.match().arena(), running);
        matchOf.values().removeIf(value -> value == running);
        for (UUID id : running.match().players()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline()) {
                releasePlayer(player);
            }
        }
    }

    /** Membership is removed; discard arena state before restoration can fail or be deferred. */
    private static void releasePlayer(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            if (player.getSpectatorTarget() != null) {
                player.setSpectatorTarget(null);
            }
            player.setGameMode(GameMode.ADVENTURE);
        }
        KitInventory.clear(player);
    }

    private void refuse(List<UUID> players, Component message) {
        for (UUID playerId : players) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
                sendBack(player);
            }
        }
    }

    private void announce(Running running, MatchPhase phase) {
        Match match = running.match();
        switch (phase) {
            case PREP -> broadcast(running, match.isExtraRound()
                    ? msg("match.prep-extra", "seconds", match.secondsLeft())
                    : msg("match.prep", "round", match.round(), "rounds", match.settings().rounds(),
                            "seconds", match.secondsLeft()));
            case COMBAT -> broadcast(running, msg("match.fight"));
            case SHOP -> broadcast(running, msg("shop.open", "round", match.round() + 1,
                    "seconds", match.secondsLeft()));
            case OVERRUN -> broadcast(running, match.settings().overrunLimitSeconds() > 0
                    ? msg("match.overrun-limited", "seconds", match.settings().overrunLimitSeconds())
                    : msg("match.overrun"));
            case WAITING, ROUND_END, MATCH_END -> {
                // Round and match results are announced with their own update.
            }
        }
    }

    private void announceResult(Running running, MatchResult result) {
        Component headline = switch (result.reason()) {
            case COMPLETED -> result.isSharedWin()
                    ? msg("match.shared-win", "teams", join(result.winners()))
                    : msg("match.won", "team", result.winners().getFirst());
            case FORFEIT -> msg("match.forfeit", "teams", join(result.winners()));
            case STOPPED -> msg("match.stopped");
            // Nobody is left to tell.
            case ABANDONED -> null;
        };
        if (headline != null) {
            broadcast(running, headline);
            announceSeeds(running);
        }
    }

    /** The seed and difficulty of each generated map played, so players can play it again. */
    private void announceSeeds(Running running) {
        List<PlayedMap> played = running.match().maps();
        for (PlayedMap map : played) {
            map.generated().ifPresent(seed -> broadcast(running, played.size() == 1
                    ? msg("match.map-seed", "seed", seed.seed(), "difficulty", seed.difficulty())
                    : msg("match.map-seed-rounds", "seed", seed.seed(), "difficulty", seed.difficulty(),
                            "from", map.firstRound(), "to", map.lastRound())));
        }
    }

    private void announceReturn(Running running, MatchResult result) {
        if (result.reason() != Reason.STOPPED && result.reason() != Reason.ABANDONED) {
            broadcast(running, msg("match.returning", "seconds", running.match().settings().matchEndSeconds()));
        }
    }

    private static String join(List<Integer> teams) {
        return teams.stream().map(String::valueOf)
                .collect(Collectors.joining(com.ninja6.ninjaarena.message.Messages.text("match.team-separator")));
    }

    private Location location(ArenaPoint point) {
        return new Location(arenaWorld.get(), point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    private String name(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        return player != null ? player.getName() : heldNames.getOrDefault(playerId, playerId.toString());
    }

    /** To the lobby, or home when the lobby cannot take the player. */
    private void sendBack(Player player) {
        boolean placed;
        try {
            placed = lobby.test(player);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not send " + player.getName() + " to the lobby.", e);
            placed = false;
        }
        if (!placed) {
            sendHome(player);
        }
    }

    private void sendHome(Player player) {
        RestoreResult result = restore.apply(player);
        if (result == RestoreResult.FAILED) {
            player.sendMessage(msg("common.restore-failed"));
        }
    }

    private void broadcast(Running running, Component message) {
        broadcast(running, message, null);
    }

    /** To everyone in the match but {@code except}, who may be {@code null}. */
    private void broadcast(Running running, Component message, UUID except) {
        for (UUID playerId : running.match().players()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && !playerId.equals(except)) {
                player.sendMessage(message);
            }
        }
    }
}
