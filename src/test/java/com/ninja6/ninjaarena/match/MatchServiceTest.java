package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.arena.ArenaStore;
import com.ninja6.ninjaarena.arena.ChunkTickets;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.arena.MemoryCopies;
import com.ninja6.ninjaarena.match.MatchResult.Reason;
import com.ninja6.ninjaarena.match.MatchService.Exit;
import com.ninja6.ninjaarena.match.MatchUpdate.Cause;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatchServiceTest {

    private static final ArenaPoint ROOM = new ArenaPoint(50.5, 64, 50.5, 0, 0);
    private static final ArenaPoint SPAWN_1 = new ArenaPoint(20.5, 64, 20.5, 90, 0);
    private static final ArenaPoint SPAWN_2 = new ArenaPoint(80.5, 64, 80.5, -90, 0);
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID CAROL = new UUID(0, 3);
    private static final UUID DAVE = new UUID(0, 4);

    /** Prep 2, combat 3, overrun limit 2, round end 1, match end 2, 2 rounds, 1 extra. */
    private static final MatchSettings SETTINGS = new MatchSettings(2, 3, 2, 1, 2, 2, 1);

    @TempDir
    Path dir;

    /** A fake player's state. */
    private static final class Fake {
        final UUID id;
        final String name;
        Location location;
        boolean online = true;
        boolean dead;
        final List<String> messages = new ArrayList<>();
        final List<Location> teleports = new ArrayList<>();
        Player proxy;

        Fake(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final Map<UUID, Fake> players = new HashMap<>();
    private final List<Runnable> nextTick = new ArrayList<>();
    private final List<UUID> restored = new ArrayList<>();
    private final List<UUID> readied = new ArrayList<>();
    private final List<MatchEvent> events = new ArrayList<>();
    private final MemoryCopies copies = new MemoryCopies();
    private World arenaWorld;
    private World overworld;
    private Plugin plugin;
    private ArenaRegistry arenas;
    private MatchService matches;
    private MatchListener listener;

    @BeforeEach
    void setUp() throws Exception {
        arenaWorld = world(ArenaWorld.NAME);
        overworld = world("world");
        ChunkTickets noTickets = new ChunkTickets() {
            @Override
            public void add(int chunkX, int chunkZ) {
            }

            @Override
            public void remove(int chunkX, int chunkZ) {
            }
        };
        arenas = new ArenaRegistry(new ArenaStore(dir.resolve("arenas.yml")),
                new ArenaGrid(256, 2048, -64, 320), enabled -> { }, noTickets, copies);
        arenas.load();
        arenas.create("dojo", new GridCell(0, 0));
        arenas.update("dojo", a -> a.withBounds(new ArenaBounds(10, 60, 10, 90, 100, 90))
                .withWaitingRoom(ROOM)
                .withTeamSpawn(1, SPAWN_1)
                .withTeamSpawn(2, SPAWN_2)
                .withEnemySpawn(new ArenaPoint(50.5, 64, 20.5, 0, 0)));
        arenas.finishEdit("dojo");
        copies.calls.clear();

        plugin = plugin();
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, events::add, player -> readied.add(player.getUniqueId()));
        listener = new MatchListener(matches);
        add(ALICE, "Alice");
        add(BOB, "Bob");
        add(CAROL, "Carol");
        add(DAVE, "Dave");
    }

    private World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Plugin plugin() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        BukkitTask task = (BukkitTask) Proxy.newProxyInstance(BukkitTask.class.getClassLoader(),
                new Class<?>[] {BukkitTask.class}, (proxy, method, args) -> null);
        BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(BukkitScheduler.class.getClassLoader(),
                new Class<?>[] {BukkitScheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "runTask" -> {
                        nextTick.add((Runnable) args[1]);
                        yield task;
                    }
                    case "runTaskTimer" -> task;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlayer" -> {
                        Fake fake = players.get((UUID) args[0]);
                        yield fake != null && fake.online ? fake.proxy : null;
                    }
                    case "getScheduler" -> scheduler;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "getLogger" -> logger;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private void add(UUID id, String name) {
        Fake fake = new Fake(id, name);
        fake.location = new Location(arenaWorld, ROOM.x(), ROOM.y(), ROOM.z());
        fake.proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "isOnline" -> fake.online;
                    case "isDead" -> fake.dead;
                    case "getLocation" -> fake.location.clone();
                    case "teleport" -> {
                        Location to = (Location) args[0];
                        fake.teleports.add(to);
                        fake.location = to;
                        yield true;
                    }
                    case "sendMessage" -> {
                        fake.messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    case "sendActionBar" -> null;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        players.put(id, fake);
    }

    private Player player(UUID id) {
        return players.get(id).proxy;
    }

    private void runNextTick() {
        List<Runnable> tasks = new ArrayList<>(nextTick);
        nextTick.clear();
        tasks.forEach(Runnable::run);
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            matches.tick();
        }
    }

    private void startTwoOnTwo() {
        matches.start("dojo", List.of(List.of(ALICE, CAROL), List.of(BOB, DAVE)));
    }

    private <T extends MatchEvent> List<T> events(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static void assertAt(ArenaPoint expected, Location actual) {
        assertEquals(ArenaWorld.NAME, actual.getWorld().getName());
        assertEquals(expected.x(), actual.getX());
        assertEquals(expected.y(), actual.getY());
        assertEquals(expected.z(), actual.getZ());
        assertEquals(expected.yaw(), actual.getYaw());
    }

    /** Kills both players of the team in one server tick and lets the round settle. */
    private void wipe(UUID first, UUID second, UUID killer) {
        matches.died(player(first), player(killer));
        matches.died(player(second), player(killer));
        runNextTick();
    }

    @Test
    void aFullMatchPlaysOutAndSendsEveryoneHome() {
        startTwoOnTwo();
        assertTrue(arenas.isHeld("dojo"));
        assertEquals(List.of("restore dojo"), copies.calls);
        assertAt(SPAWN_1, players.get(ALICE).teleports.getLast());
        assertAt(SPAWN_1, players.get(CAROL).teleports.getLast());
        assertAt(SPAWN_2, players.get(BOB).teleports.getLast());
        assertEquals(List.of(ALICE, CAROL, BOB, DAVE), readied);
        MatchPhaseChangeEvent first = (MatchPhaseChangeEvent) events.get(0);
        assertEquals(MatchPhase.WAITING, first.from());
        assertEquals(MatchPhase.PREP, first.to());
        assertEquals(1, ((RoundStartEvent) events.get(1)).round());

        MatchView view = matches.matchOf(ALICE).orElseThrow();
        assertEquals("dojo", view.arena());
        assertEquals(1, view.teamOf(ALICE));
        assertEquals(2, matches.matchOf(DAVE).orElseThrow().teamOf(DAVE));
        assertEquals(view, matches.match("DOJO").orElseThrow());
        assertEquals(List.of(view), matches.matches());
        assertTrue(players.get(ALICE).messages.contains("Round 1 of 2. The fight starts in 2 seconds."));

        // Round 1: team 1 wipes team 2.
        ticks(2);
        assertEquals(MatchPhase.COMBAT, view.phase());
        wipe(BOB, DAVE, ALICE);
        assertEquals(MatchPhase.ROUND_END, view.phase());
        List<PlayerEliminatedEvent> outs = events(PlayerEliminatedEvent.class);
        assertEquals(BOB, outs.get(0).player());
        assertEquals(ALICE, outs.get(0).killer().orElseThrow());
        assertEquals(Cause.DIED, outs.get(0).cause());
        assertEquals(1, events(RoundEndEvent.class).getFirst().result().winner());
        assertTrue(players.get(CAROL).messages.contains("Team 1 wins round 1."));

        // Round 2: everyone is placed again, then team 1 wins again.
        ticks(1);
        assertEquals(2, view.round());
        assertEquals(2, players.get(BOB).teleports.size());
        assertEquals(8, readied.size());
        ticks(2);
        wipe(BOB, DAVE, CAROL);

        ticks(1);
        assertEquals(MatchPhase.MATCH_END, view.phase());
        MatchResult result = events(MatchEndEvent.class).getFirst().result();
        assertEquals(Reason.COMPLETED, result.reason());
        assertEquals(List.of(1), result.winners());
        assertEquals(2, result.rounds().size());
        assertTrue(players.get(BOB).messages.contains("Team 1 wins the match!"));
        assertTrue(restored.isEmpty());
        assertTrue(arenas.isHeld("dojo"));

        ticks(2);
        assertTrue(matches.matches().isEmpty());
        assertFalse(matches.isInMatch(ALICE));
        assertEquals(List.of(ALICE, CAROL, BOB, DAVE), restored);
        assertEquals(List.of("restore dojo", "restore dojo"), copies.calls);
        assertFalse(arenas.isHeld("dojo"));
        assertTrue(arenas.isAvailable("dojo"));

        List<MatchPhase> phases = events(MatchPhaseChangeEvent.class).stream().map(MatchPhaseChangeEvent::to).toList();
        assertEquals(List.of(MatchPhase.PREP, MatchPhase.COMBAT, MatchPhase.ROUND_END, MatchPhase.PREP,
                MatchPhase.COMBAT, MatchPhase.ROUND_END, MatchPhase.MATCH_END), phases);
    }

    @Test
    void theRoundTimerRunsOutIntoOverrun() {
        startTwoOnTwo();
        ticks(2 + 3);
        MatchView view = matches.match("dojo").orElseThrow();
        assertEquals(MatchPhase.OVERRUN, view.phase());
        assertTrue(view.isMaxIntensity());
        assertTrue(events(MatchPhaseChangeEvent.class).getLast().to().isMaxIntensity());
        assertTrue(players.get(ALICE).messages.contains(
                "Overrun! Time is up: the round goes on until one team is left, for at most 2 seconds."));
        ticks(2);
        assertTrue(events(RoundEndEvent.class).getFirst().result().isDraw());
    }

    @Test
    void playersWhoGoDownInTheSameTickDrawTheRound() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        ticks(2);
        matches.died(player(ALICE), player(BOB));
        matches.died(player(BOB), player(ALICE));
        assertTrue(events(RoundEndEvent.class).isEmpty());
        runNextTick();
        assertTrue(events(RoundEndEvent.class).getFirst().result().isDraw());
    }

    @Test
    void playersAreShelteredOutsideCombatAndRespawnAtTheirTeamSpawn() {
        startTwoOnTwo();
        assertTrue(matches.isSheltered(ALICE));
        assertAt(SPAWN_2, matches.respawnPoint(BOB).orElseThrow());
        ticks(2);
        assertFalse(matches.isSheltered(ALICE));
        assertFalse(matches.isSheltered(UUID.randomUUID()));
        assertTrue(matches.respawnPoint(UUID.randomUUID()).isEmpty());

        // A death in prep does not happen through damage, but the void still counts.
        matches.died(player(ALICE), null);
        runNextTick();
        assertFalse(matches.matchOf(ALICE).orElseThrow().isAlive(ALICE));
        assertTrue(matches.isInMatch(ALICE));
    }

    @Test
    void aPlayerStillDeadAtTheNextRoundIsNotTeleported() {
        startTwoOnTwo();
        ticks(2);
        players.get(DAVE).dead = true;
        matches.died(player(DAVE), null);
        matches.died(player(BOB), null);
        runNextTick();
        ticks(1);
        assertEquals(1, players.get(DAVE).teleports.size());
        assertEquals(2, players.get(BOB).teleports.size());
    }

    @Test
    void theLeaveCommandSendsThePlayerHomeAtOnceAndCanDecideTheRound() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        ticks(2);
        assertTrue(matches.leave(player(BOB), Exit.COMMAND));
        assertEquals(List.of(BOB), restored);
        assertTrue(players.get(BOB).messages.contains("You left the match."));
        assertTrue(players.get(ALICE).messages.contains("Bob left the match."));
        assertEquals(Cause.LEFT, events(PlayerEliminatedEvent.class).getFirst().cause());
        assertEquals(BOB, events(PlayerLeaveMatchEvent.class).getFirst().player());
        assertFalse(matches.leave(player(BOB), Exit.COMMAND));

        runNextTick();
        assertEquals(1, events(RoundEndEvent.class).getFirst().result().winner());
        ticks(1);
        MatchResult result = events(MatchEndEvent.class).getFirst().result();
        assertEquals(Reason.FORFEIT, result.reason());
        ticks(2);
        assertEquals(List.of(BOB, ALICE), restored);
        assertFalse(arenas.isHeld("dojo"));
    }

    @Test
    void aTeleportOutOfTheArenaWorldLeavesButOneInsideItDoesNot() {
        startTwoOnTwo();
        Fake alice = players.get(ALICE);
        listener.onTeleport(new PlayerTeleportEvent(alice.proxy, alice.location.clone(),
                new Location(arenaWorld, 30, 64, 30)));
        assertTrue(matches.isInMatch(ALICE));

        listener.onTeleport(new PlayerTeleportEvent(alice.proxy, alice.location.clone(),
                new Location(overworld, 0, 64, 0)));
        assertFalse(matches.isInMatch(ALICE));
        assertTrue(restored.isEmpty());
        runNextTick();
        assertEquals(List.of(ALICE), restored);
    }

    @Test
    void aDisconnectLeavesTheRestoreToTheNextJoin() {
        startTwoOnTwo();
        players.get(CAROL).online = false;
        assertTrue(matches.leave(player(CAROL), Exit.QUIT));
        runNextTick();
        assertTrue(restored.isEmpty());
        assertEquals(List.of(ALICE), matches.match("dojo").orElseThrow().playersOf(1));
    }

    @Test
    void theLastPlayerLeavingClosesTheMatchAndFreesTheArena() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        matches.leave(player(ALICE), Exit.COMMAND);
        matches.leave(player(BOB), Exit.QUIT);
        assertEquals(Reason.ABANDONED, events(MatchEndEvent.class).getFirst().result().reason());
        assertTrue(matches.matches().isEmpty());
        assertFalse(arenas.isHeld("dojo"));
        assertEquals(List.of("restore dojo", "restore dojo"), copies.calls);
        runNextTick();
        assertEquals(List.of(ALICE), restored);
    }

    @Test
    void aFailedResetAtTheStartSendsEveryoneHome() {
        copies.failing = true;
        startTwoOnTwo();
        assertFalse(arenas.isHeld("dojo"));
        assertFalse(arenas.isAvailable("dojo"));
        assertTrue(matches.matches().isEmpty());
        assertFalse(matches.isInMatch(ALICE));
        assertEquals(List.of(ALICE, CAROL, BOB, DAVE), restored);
        assertTrue(players.get(ALICE).messages.contains("Arena dojo could not be prepared, so the match could not"
                + " start."));
        assertTrue(events.isEmpty());
        assertTrue(players.get(ALICE).teleports.isEmpty());
    }

    @Test
    void aFailedResetAtTheEndStillSendsEveryoneHomeAndReleasesTheArena() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        ticks(2);
        matches.died(player(BOB), player(ALICE));
        runNextTick();
        ticks(1);
        ticks(1);
        matches.died(player(BOB), player(ALICE));
        runNextTick();
        ticks(1);
        assertEquals(MatchPhase.MATCH_END, matches.match("dojo").orElseThrow().phase());

        copies.failing = true;
        ticks(2);
        assertEquals(List.of(ALICE, BOB), restored);
        assertFalse(arenas.isHeld("dojo"));
        assertFalse(arenas.isAvailable("dojo"));
        assertTrue(matches.matches().isEmpty());
    }

    @Test
    void anUnavailableArenaRefusesTheMatch() throws Exception {
        arenas.startEdit("dojo");
        startTwoOnTwo();
        assertTrue(matches.matches().isEmpty());
        assertEquals(List.of(ALICE, CAROL, BOB, DAVE), restored);
        assertTrue(players.get(BOB).messages.contains("Arena dojo is not available, so the match could not start."));
        assertEquals(List.of(), copies.calls);
    }

    @Test
    void aSecondMatchInAHeldArenaIsRefused() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        matches.start("dojo", List.of(List.of(CAROL), List.of(DAVE)));
        assertEquals(List.of(CAROL, DAVE), restored);
        assertEquals(List.of(ALICE, BOB), matches.match("dojo").orElseThrow().players());
    }

    @Test
    void offlinePlayersAreLeftOutAndAnEmptyRosterStartsNothing() {
        players.values().forEach(fake -> fake.online = false);
        startTwoOnTwo();
        assertTrue(matches.matches().isEmpty());
        assertFalse(arenas.isHeld("dojo"));
        assertEquals(List.of(), copies.calls);

        players.get(ALICE).online = true;
        players.get(BOB).online = true;
        startTwoOnTwo();
        assertEquals(List.of(ALICE, BOB), matches.match("dojo").orElseThrow().players());
    }

    @Test
    void anUnexpectedFailureReleasesTheArenaAndThrowsSoTheWaitingRoomSendsPlayersHome() throws Exception {
        MatchService failing = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, event -> {
            throw new IllegalStateException("listener broke");
        }, player -> { });
        assertThrows(IllegalStateException.class, () -> failing.start("dojo", List.of(List.of(ALICE), List.of(BOB))));
        assertFalse(arenas.isHeld("dojo"));
        assertFalse(failing.isInMatch(ALICE));

        WaitingRoomService rooms = new WaitingRoomService(plugin, arenas, new WaitingRoomSettings(2, 1, 4),
                player -> {
                    restored.add(player.getUniqueId());
                    return RestoreResult.RESTORED;
                }, failing);
        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        rooms.tick();
        assertEquals(List.of(ALICE, BOB), restored);
        assertTrue(players.get(ALICE).messages.contains("The match could not start."));
        assertFalse(arenas.isHeld("dojo"));
    }

    @Test
    void closingStopsEveryMatchAndSendsEveryoneHome() {
        startTwoOnTwo();
        Fake alice = players.get(ALICE);
        listener.onTeleport(new PlayerTeleportEvent(alice.proxy, alice.location.clone(),
                new Location(overworld, 0, 64, 0)));
        matches.close();
        assertEquals(Reason.STOPPED, events(MatchEndEvent.class).getFirst().result().reason());
        assertTrue(restored.containsAll(List.of(ALICE, BOB, CAROL, DAVE)));
        assertEquals(4, restored.size());
        assertTrue(matches.matches().isEmpty());
        assertFalse(arenas.isHeld("dojo"));
        assertEquals(List.of("restore dojo", "restore dojo"), copies.calls);

        runNextTick();
        assertEquals(4, restored.size());
    }

    @Test
    void customStandingsApplyToMatchesStartedAfterwards() {
        matches.setStandings((match, team) -> team == 2 ? 10 : 0);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        ticks(2);
        matches.died(player(BOB), player(ALICE));
        runNextTick();
        ticks(1 + 2);
        matches.died(player(BOB), player(ALICE));
        runNextTick();
        ticks(1);
        MatchResult result = events(MatchEndEvent.class).getFirst().result();
        assertEquals(List.of(2), result.winners());
        assertEquals(2, result.standings().getLast().roundsWon());
    }
}
