package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.director.MobSpawner;
import com.ninja6.ninjaarena.director.ShadowStalker;
import com.ninja6.ninjaarena.director.SpawnSettings;
import com.ninja6.ninjaarena.director.StalkerSettings;
import com.ninja6.ninjaarena.map.ArenaMaps;
import com.ninja6.ninjaarena.map.FakeBlocks;
import com.ninja6.ninjaarena.map.GeneratedMaps;
import com.ninja6.ninjaarena.map.TickCellWriter;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.room.Room;
import com.ninja6.ninjaarena.room.RoomLobby;
import com.ninja6.ninjaarena.room.RoomService;
import com.ninja6.ninjaarena.room.SeedChoice;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.spectate.SpectatorSettings;
import com.ninja6.ninjaarena.spectate.Spectators;
import com.ninja6.ninjaarena.terrain.ArenaLayout;
import com.ninja6.ninjaarena.terrain.BlockKind;
import com.ninja6.ninjaarena.terrain.ChallengeMinimums;
import com.ninja6.ninjaarena.terrain.MapSearch;
import com.ninja6.ninjaarena.terrain.MovementModel;
import com.ninja6.ninjaarena.terrain.SideDimensions;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.CrossWorldTransfers;
import com.ninja6.ninjaarena.world.LobbyWorld;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rooms on generated maps end to end, with the real match engine, arena registry and slot
 * cells: the map lifecycle, every lookup by name, the edit lock and the seed fallback.
 */
class GeneratedRoomFlowTest extends MatchFixture {

    private final World lobbyWorld = world(LobbyWorld.NAME);
    private final Set<UUID> lobby = new LinkedHashSet<>();
    private final CrossWorldTransfers transfers = new CrossWorldTransfers();
    private final FakeBlocks blocks = new FakeBlocks();
    private final TickCellWriter writer = new TickCellWriter(blocks, 1 << 21);
    private GeneratedMaps generated;
    private ArenaMaps maps;
    private RoomService rooms;
    private Path store;

    @BeforeEach
    void setUpGeneratedMaps() throws Exception {
        arenas.create("garden", new GridCell(1, 0));
        arenas.update("garden", a -> a.withBounds(new ArenaBounds(266, 60, 10, 346, 100, 90))
                .withTeamSpawn(1, new ArenaPoint(276.5, 64, 20.5, 90, 0))
                .withTeamSpawn(2, new ArenaPoint(336.5, 64, 80.5, -90, 0))
                .withEnemySpawn(new ArenaPoint(306.5, 64, 20.5, 0, 0)));
        arenas.finishEdit("garden");
        store = dir.resolve("arenas.yml");
        for (Fake fake : players.values()) {
            fake.location = new Location(overworld, 0, 70, 0);
            fake.permissions.add(NinjaArenaCommand.PLAY);
            fake.permissions.add(RoomService.CREATE);
        }
        generated = new GeneratedMaps(arenas.grid(), () -> arenas.all().stream().map(Arena::cell).toList(),
                arenas::isReadable, writer, blocks.ticketsView(), Runnable::run, Runnable::run, plugin.getLogger());
        arenas.setReservedCells(generated::isReserved);
        arenas.setSlotCellsInUse(generated::inUse);
        maps = new ArenaMaps(arenas, generated);
        matches = new MatchService(plugin, maps, () -> arenaWorld, SETTINGS, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, event -> {
            events.add(event);
            switch (event) {
                case PlayerEliminatedEvent eliminated -> deaths.onEliminated(eliminated);
                case PlayerLeaveMatchEvent left -> deaths.onLeave(left);
                case RoundStartEvent started -> deaths.onRoundStart(started);
                default -> { }
            }
        }, player -> readied.add(player.getUniqueId()));
        deaths = new MatchDeathListener(plugin, matches, maps);
        listener = new MatchListener(matches, deaths);
        RoomLobby fakeLobby = new RoomLobby() {
            @Override
            public boolean isMember(UUID player) {
                return lobby.contains(player);
            }

            @Override
            public List<UUID> members() {
                return List.copyOf(lobby);
            }

            @Override
            public Optional<String> join(Player player) {
                enter(player);
                return Optional.empty();
            }

            @Override
            public boolean enter(Player player) {
                players.get(player.getUniqueId()).location = new Location(lobbyWorld, 0, 64, 0);
                lobby.add(player.getUniqueId());
                return true;
            }
        };
        rooms = new RoomService(plugin, arenas, matches, fakeLobby, () -> new WaitingRoomSettings(2), transfers,
                () -> () -> { }, 2);
        rooms.setGeneratedMaps(generated);
        matches.setLobby(rooms::enterLobby);
        MatchListener crossing = new MatchListener(matches, deaths, transfers);
        teleportEvents = event -> {
            if (!PluginWorlds.isLobby(event.getTo())) {
                lobby.remove(event.getPlayer().getUniqueId());
            }
            deaths.onTeleport(event);
            if (!event.isCancelled()) {
                crossing.onTeleport(event);
            }
        };
        assertEquals(2, generated.cells().size());
        drain();
        assertEquals(List.of(), generated.inUse(), "the slot cells are cleared when they are reserved");
    }

    /** Runs the cell writer and the next-tick tasks until no work is left. */
    private void drain() {
        for (int i = 0; i < 1000 && (writer.pending() > 0 || !nextTick.isEmpty()); i++) {
            writer.tick();
            runNextTick();
        }
        assertEquals(0, writer.pending());
    }

    private Room openWith(UUID host, UUID... guests) {
        rooms.create(player(host));
        for (UUID guest : guests) {
            rooms.invite(player(host), players.get(guest).name);
            rooms.accept(player(guest), players.get(host).name);
        }
        return rooms.hostedBy(host).orElseThrow();
    }

    private void playToTheEnd(String arena) {
        for (int i = 0; i < 100 && matches.match(arena).isPresent(); i++) {
            matches.tick();
            runNextTick();
        }
        assertTrue(matches.match(arena).isEmpty(), "the match closed");
    }

    private List<String> messages(UUID id) {
        return players.get(id).messages;
    }

    private static long nonAir(long seed, int difficulty, int teams) {
        MapSearch.Accepted accepted = assertInstanceOf(MapSearch.Accepted.class, GeneratedMaps.Search.DEFAULT
                .search(seed, difficulty, teams, SideDimensions.DEFAULT));
        ArenaLayout layout = accepted.layout();
        long count = 0;
        for (int x = 0; x < layout.width(); x++) {
            for (int z = 0; z < layout.length(); z++) {
                for (int y = 0; y < layout.height(); y++) {
                    if (layout.at(x, y, z) != BlockKind.AIR) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private void die(UUID id) {
        players.get(id).dead = true;
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                new Class<?>[] {DamageSource.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        var event = new PlayerDeathEvent(player(id), source, new ArrayList<>(List.of(new ItemStack() { })), 8,
                Component.empty(), true);
        deaths.onDeath(event);
        deaths.onDeathBetweenRounds(event);
        listener.onDeath(event);
        var respawn = new PlayerRespawnEvent(player(id), new Location(overworld, 0.5, 65, 0.5),
                false, false, false, PlayerRespawnEvent.RespawnReason.DEATH);
        listener.onRespawn(respawn);
        players.get(id).dead = false;
        players.get(id).location = respawn.getRespawnLocation().clone();
        deaths.onPostRespawn(new PlayerPostRespawnEvent(player(id), respawn.getRespawnLocation(),
                false, false, false, PlayerRespawnEvent.RespawnReason.DEATH));
    }

    /** A seed whose first try is rejected at difficulty 5, for a search with no retries. */
    private static long rejectedSeed() {
        for (long seed = 1; seed < 200; seed++) {
            if (MapSearch.search(seed, 5, 2, SideDimensions.DEFAULT, MovementModel.DEFAULT,
                    ChallengeMinimums.forDifficulty(5), 0) instanceof MapSearch.Failed) {
                return seed;
            }
        }
        throw new AssertionError("no rejected seed");
    }

    @Test
    void aRoomPlaysAWholeMatchOnAGeneratedMapAndEveryLookupFindsIt() throws Exception {
        String stored = Files.readString(store);
        FileTime written = Files.getLastModifiedTime(store);
        Room room = openWith(ALICE, BOB, CAROL, DAVE);
        assertEquals("dojo", room.arena());

        rooms.playGenerated(room, 42, 1);
        String name = room.arena();
        assertTrue(Arena.isGeneratedName(name), name);
        assertTrue(rooms.hasLease(room));
        assertEquals("Generating the room's map from seed 42 at difficulty 1...", messages(ALICE).getLast());
        assertTrue(maps.get(name).isEmpty(), "not placed yet");
        rooms.start(player(ALICE), null);
        assertEquals("Arena " + name + " is not ready for a match yet.", messages(ALICE).getLast());
        assertFalse(room.isPlaying());

        drain();
        assertEquals("The room's generated map is ready: seed 42.", messages(ALICE).getLast());
        assertEquals(Optional.of(new SeedChoice(42, 1)), room.lastGoodSeed());
        Arena map = maps.get(name).orElseThrow();
        assertTrue(generated.isReserved(map.cell()));
        assertFalse(arenas.all().stream().anyMatch(arena -> arena.name().equals(name)), "not a registry arena");
        assertFalse(rooms.pickableMaps().contains(name));

        rooms.start(player(ALICE), null);
        assertTrue(room.isPlaying());
        MatchView match = matches.match(name).orElseThrow();
        assertTrue(maps.isHeld(name));
        int aliceTeam = match.teamOf(ALICE);
        assertAt(map.teamSpawns().get(aliceTeam - 1), players.get(ALICE).location);

        // Spectating and portals to a live match find the generated map.
        var restores = new RestoreService(new SnapshotService(new SnapshotStore(dir.resolve("snapshots")),
                plugin.getLogger()), (player, snapshot) -> { }, plugin.getLogger());
        Spectators viewers = new Spectators(plugin, maps, matches, restores, () -> arenaWorld,
                new SpectatorSettings(true, 4));
        assertEquals(Optional.of(map.teamSpawns().getFirst()), viewers.destination(name));
        // The enemy director's spawner finds its spawns and bounds.
        MobGoals goals = (MobGoals) Proxy.newProxyInstance(MobGoals.class.getClassLoader(),
                new Class<?>[] {MobGoals.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        assertInstanceOf(MobSpawner.class, MobSpawner.factory(maps, () -> arenaWorld, plugin.getServer()::getPlayer,
                SpawnSettings.DEFAULTS, new ShadowStalker(StalkerSettings.DEFAULTS, goals, () -> 0),
                plugin.getLogger()).apply(match));
        // Death handling: a spectator follows a teammate who is inside the map's bounds.
        UUID teammate = match.playersOf(aliceTeam).stream().filter(id -> !id.equals(ALICE)).findFirst()
                .orElseThrow();
        die(ALICE);
        assertTrue(deaths.isSpectating(ALICE));
        assertEquals(GameMode.SPECTATOR, players.get(ALICE).gameMode);
        deaths.tick();
        assertSame(player(teammate), player(ALICE).getSpectatorTarget());

        GridCell cell = map.cell();
        playToTheEnd(name);
        assertFalse(room.isPlaying());
        // Reset by clearing the cell and generating the map again, in the other free cell.
        assertTrue(generated.inUse().contains(cell), "the played cell is being cleared");
        drain();
        assertTrue(maps.isAvailable(name), "the next match has its map again");
        assertEquals(nonAir(42, 1, 2), blocks.countSolid(), "one map is placed and the played one is cleared");
        assertEquals(0, blocks.countSolid(generated.region(cell)), "the played cell is empty");
        assertEquals(stored, Files.readString(store), "arenas.yml is never written for a generated map");
        assertEquals(written, Files.getLastModifiedTime(store));
    }

    @Test
    void anEditIsRefusedWhileASlotCellIsLeasedAndASlotWaitsForAnEdit() throws Exception {
        Room room = openWith(ALICE, BOB);
        rooms.playGenerated(room, 42, 1);
        ArenaException refused = assertThrows(ArenaException.class, () -> arenas.startEdit("garden"));
        assertEquals("arena.error.edit-while-generated", refused.key());
        assertEquals("arena.error.edit-while-generated",
                assertThrows(ArenaException.class, () -> arenas.create("fresh", null)).key());
        drain();
        assertThrows(ArenaException.class, () -> arenas.startEdit("garden"), "a placed map keeps the lock");

        rooms.setArena(room, "dojo");
        assertThrows(ArenaException.class, () -> arenas.startEdit("garden"), "locked until the cell is cleared");
        drain();
        assertEquals(List.of(), generated.inUse());
        assertTrue(blocks.tickets.isEmpty());
        arenas.startEdit("garden");

        // While an arena is in edit mode, a room asking for a generated map waits for a slot.
        Room other = openWith(CAROL, DAVE);
        rooms.playGenerated(other, 7, 1);
        assertFalse(rooms.hasLease(other));
        assertEquals(List.of(), generated.inUse());
        arenas.finishEdit("garden");
        rooms.tick();
        assertTrue(rooms.hasLease(other));
        assertFalse(generated.inUse().isEmpty());
    }

    @Test
    void aFailedSearchFallsBackToTheLastGoodSeedElseAHandBuiltArenaAndSaysWhich() {
        long rejected = rejectedSeed();
        generated.setSearch((seed, difficulty, teams, size) -> MapSearch.search(seed, difficulty, teams, size,
                MovementModel.DEFAULT, ChallengeMinimums.forDifficulty(difficulty), 0));
        Room room = openWith(ALICE, BOB);

        rooms.playGenerated(room, rejected, 5);
        drain();
        assertEquals("No playable map came from seed " + rejected + " in 1 tries and there is no earlier good seed,"
                + " so the room plays the hand-built arena dojo.", messages(BOB).getLast());
        assertEquals("dojo", room.arena());
        assertTrue(room.generated().isEmpty());
        assertTrue(rooms.hasLease(room));
        assertEquals(List.of(), generated.inUse(), "the failed map's cell is cleared and free");
        assertTrue(blocks.tickets.isEmpty());

        rooms.playGenerated(room, 42, 1);
        drain();
        String good = room.arena();
        assertTrue(maps.isAvailable(good));
        rooms.playGenerated(room, rejected, 5);
        drain();
        assertTrue(messages(BOB).contains("No playable map came from seed " + rejected + " in 1 tries, so the room"
                + " plays the last good seed, 42."), String.valueOf(messages(BOB)));
        assertEquals(Optional.of(new SeedChoice(rejected, 5)), room.generated(), "the room keeps asking for its seed");
        assertTrue(maps.isAvailable(room.arena()), "the last good seed's map is placed");
        assertEquals(nonAir(42, 1, 2), blocks.countSolid());
        rooms.start(player(ALICE), null);
        assertTrue(room.isPlaying());
    }

    @Test
    void arenaCreateSkipsTheReservedCellsAndRefusesOneByName() throws Exception {
        List<GridCell> reserved = generated.cells();
        Arena fresh = arenas.create("fresh", null);
        assertFalse(reserved.contains(fresh.cell()));
        arenas.finishEdit("fresh");
        ArenaException refused = assertThrows(ArenaException.class, () -> arenas.create("other", reserved.getFirst()));
        assertEquals("arena.error.cell-reserved", refused.key());
    }

    @Test
    void aLowerMaxActiveNeverTakesTheCellOfALeasedSlot() {
        Room first = openWith(ALICE, BOB);
        Room second = openWith(CAROL, DAVE);
        rooms.playGenerated(first, 42, 1);
        rooms.playGenerated(second, 43, 1);
        drain();
        List<GridCell> cells = generated.cells();
        assertEquals(cells, generated.inUse());

        rooms.setMaxActive(1);
        assertEquals(cells, generated.cells(), "both leased cells stay reserved");
        assertTrue(maps.isAvailable(first.arena()) && maps.isAvailable(second.arena()));
        rooms.leave(player(DAVE));
        rooms.leave(player(CAROL));
        drain();
        assertEquals(1, generated.cells().size(), "given up once free");
        assertEquals(List.of(generated.cellOf(first.arena()).orElseThrow()), generated.cells());
        rooms.setMaxActive(2);
        assertEquals(cells, generated.cells(), "raised again, the same cells");
    }
}
