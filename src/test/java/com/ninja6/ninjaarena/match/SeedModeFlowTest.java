package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.entity.ai.MobGoals;
import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.arena.MapSeed;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.director.MatchDirector;
import com.ninja6.ninjaarena.director.MobSpawner;
import com.ninja6.ninjaarena.director.ShadowStalker;
import com.ninja6.ninjaarena.director.SpawnSettings;
import com.ninja6.ninjaarena.director.StalkerSettings;
import com.ninja6.ninjaarena.map.ArenaMaps;
import com.ninja6.ninjaarena.map.BudgetedCellWriter;
import com.ninja6.ninjaarena.map.FakeBlocks;
import com.ninja6.ninjaarena.map.FakeWriters;
import com.ninja6.ninjaarena.map.GeneratedMaps;
import com.ninja6.ninjaarena.map.SeedPolicy;
import com.ninja6.ninjaarena.map.SeedSettings;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.room.AdminMaps;
import com.ninja6.ninjaarena.room.Room;
import com.ninja6.ninjaarena.room.RoomLobby;
import com.ninja6.ninjaarena.room.RoomService;
import com.ninja6.ninjaarena.room.SeedChoice;
import com.ninja6.ninjaarena.stats.MatchRecord;
import com.ninja6.ninjaarena.stats.StatsTracker;
import com.ninja6.ninjaarena.terrain.MapSearch;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.CrossWorldTransfers;
import com.ninja6.ninjaarena.world.LobbyWorld;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Seed modes with rooms, the real match engine and slot cells: choosing generated maps,
 * refusing a seed another slot plays, the timers and requests never touching a running
 * match, and generating the map again in each shop break.
 */
class SeedModeFlowTest extends MatchFixture {

    private static final ShopSettings SHOP = new ShopSettings(true, 4, 2, 3);
    private static final long HOUR = 3_600_000;

    private final World lobbyWorld = world(LobbyWorld.NAME);
    private final Location shopSpot = new Location(lobbyWorld, 31, 65, 12.5, 180, 0);
    private final Set<UUID> lobby = new LinkedHashSet<>();
    private final CrossWorldTransfers transfers = new CrossWorldTransfers();
    private final FakeBlocks blocks = new FakeBlocks();
    private final BudgetedCellWriter writer = FakeWriters.perTick(blocks, 1 << 21);
    /** Searches already made, by seed, difficulty and teams: the same map every time. */
    private final Map<List<Long>, MapSearch.Outcome> searched = new HashMap<>();
    /** Seeds whose search fails. */
    private final Set<Long> failing = new HashSet<>();
    /** Random seeds handed out in order; 500, 501, ... once these run out. */
    private final Deque<Long> randoms = new ArrayDeque<>();
    private long nextRandom = 500;
    private long now = 10 * HOUR;
    private GeneratedMaps generated;
    private ArenaMaps maps;
    private RoomService rooms;
    private SeedPolicy seeds;

    @BeforeEach
    void setUpSeedModes() throws Exception {
        arenas.create("garden", new GridCell(1, 0));
        arenas.update("garden", a -> a.withBounds(new ArenaBounds(266, 60, 10, 346, 100, 90))
                .withTeamSpawn(1, new ArenaPoint(276.5, 64, 20.5, 90, 0))
                .withTeamSpawn(2, new ArenaPoint(336.5, 64, 80.5, -90, 0))
                .withEnemySpawn(new ArenaPoint(306.5, 64, 20.5, 0, 0)));
        arenas.finishEdit("garden");
        for (Fake fake : players.values()) {
            fake.location = new Location(overworld, 0, 70, 0);
            fake.permissions.add(NinjaArenaCommand.PLAY);
            fake.permissions.add(RoomService.CREATE);
        }
        players.get(ALICE).permissions.add(NinjaArenaCommand.MAP_CHANGE);
        players.get(CAROL).permissions.add(NinjaArenaCommand.MAP_CHANGE);
        generated = new GeneratedMaps(arenas.grid(), () -> arenas.all().stream().map(Arena::cell).toList(),
                arenas::isReadable, writer, blocks.ticketsView(), Runnable::run, Runnable::run, plugin.getLogger());
        generated.setSearch((seed, difficulty, teams, size) -> failing.contains(seed)
                ? new MapSearch.Failed(seed, List.of())
                : searched.computeIfAbsent(List.of(seed, (long) difficulty, (long) teams),
                        key -> GeneratedMaps.Search.DEFAULT.search(seed, difficulty, teams, size)));
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
                // As the plugin registers the room service as a listener.
                case MatchPhaseChangeEvent phase -> rooms.onPhase(phase);
                default -> { }
            }
        }, player -> readied.add(player.getUniqueId()));
        matches.setShopSettings(SHOP);
        matches.setShopArea(() -> shopSpot);
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
        matches.setMapReady(rooms::mapReady);
        transfers.allow(matches::crossesWorlds);
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
        drain();
    }

    private void useSeedModes(String yaml) {
        SeedSettings settings = SeedSettings.from(YamlConfiguration.loadConfiguration(new StringReader(yaml))
                .getConfigurationSection("maps"));
        seeds = new SeedPolicy(settings, () -> now, () -> randoms.isEmpty() ? nextRandom++ : randoms.poll(),
                plugin.getLogger()::info);
        rooms.setSeedPolicy(seeds);
        drain();
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

    /** A room of the host and guest on generated maps from the seed modes, its map placed. */
    private Room generatedRoom(UUID host, UUID guest) {
        Room room = openWith(host, guest);
        rooms.mapSetGenerated(player(host), null, null);
        drain();
        assertTrue(maps.isAvailable(room.arena()), room.arena());
        return room;
    }

    private void tickUntil(String arena, MatchPhase phase) {
        for (int i = 0; i < 60 && matches.match(arena).orElseThrow().phase() != phase; i++) {
            matches.tick();
            runNextTick();
        }
        assertEquals(phase, matches.match(arena).orElseThrow().phase());
    }

    private void playToTheEnd(String arena) {
        for (int i = 0; i < 200 && matches.match(arena).isPresent(); i++) {
            matches.tick();
            runNextTick();
        }
        assertTrue(matches.match(arena).isEmpty(), "the match closed");
    }

    private List<String> messages(UUID id) {
        return players.get(id).messages;
    }

    private long seedOf(Room room) {
        return room.generated().orElseThrow().seed();
    }

    @Test
    void withNoHandBuiltArenaReadyANewRoomPlaysGeneratedMapsFromTheSeedModes() {
        useSeedModes("maps: {default: {difficulty: 1}}");
        randoms.add(42L);
        Room dojo = openWith(ALICE);
        Room garden = openWith(BOB);
        assertEquals(List.of("dojo", "garden"), List.of(dojo.arena(), garden.arena()), "hand-built first");
        Room third = openWith(CAROL);
        assertTrue(Arena.isGeneratedName(third.arena()), third.arena());
        assertTrue(third.playsGenerated());
        assertFalse(third.isPinned());
        assertEquals(2, third.settings().teams());
        // Both slots are leased by the hand-built rooms, so it queues for one.
        assertFalse(rooms.hasLease(third));
        rooms.leave(player(BOB));
        drain();
        assertTrue(rooms.hasLease(third));
        assertEquals(Optional.of(new SeedChoice(42, 1)), third.generated());
        assertTrue(maps.isAvailable(third.arena()));
        rooms.mapList(player(CAROL));
        assertEquals("- generated (current)", messages(CAROL).getLast());
    }

    @Test
    void withoutTheSeedModesANewRoomStillGetsAHandBuiltArena() {
        openWith(ALICE);
        openWith(BOB);
        assertEquals("dojo", openWith(CAROL).arena(), "the earlier default when no seed modes are set");
    }

    @Test
    void rule6ASeedSetByHandThatAnotherActiveSlotPlaysIsRefusedWithAMessage() {
        useSeedModes("maps: {}");
        Room first = openWith(ALICE, BOB);
        rooms.mapSetGenerated(player(ALICE), 42L, 1);
        drain();
        assertTrue(maps.isAvailable(first.arena()));
        assertTrue(first.isPinned());
        assertTrue(messages(BOB).contains("Alice set the room's map to seed 42 at difficulty 1."));

        Room second = openWith(CAROL, DAVE);
        String before = second.arena();
        rooms.mapSetGenerated(player(CAROL), 42L, 3);
        assertEquals("Seed 42 is played by another arena slot now, and seeds are not shared here. Choose another"
                + " seed.", messages(CAROL).getLast());
        assertEquals(before, second.arena(), "not silently replaced: nothing changed");
        assertFalse(second.playsGenerated());
        // The seed of a map from the seed modes counts too, and so does one set by hand and still queued.
        rooms.mapSetGenerated(player(CAROL), 43L, 1);
        drain();
        assertEquals(43, seedOf(second));
        rooms.mapSetGenerated(player(ALICE), 43L, 1);
        assertTrue(messages(ALICE).getLast().startsWith("Seed 43 is played by another arena slot now"));
        rooms.mapSetGenerated(player(ALICE), 42L, 1);
        assertEquals("The room already plays seed 42 at difficulty 1.", messages(ALICE).getLast());

        rooms.mapSetGenerated(player(ALICE), 7L, 9);
        assertEquals("The difficulty must be a whole number from 1 to 5.", messages(ALICE).getLast());
        players.get(ALICE).permissions.remove(NinjaArenaCommand.MAP_CHANGE);
        rooms.mapSetGenerated(player(ALICE), 7L, 1);
        assertEquals("You do not have permission to change your room's map.", messages(ALICE).getLast());
        rooms.mapNext(player(ALICE));
        assertEquals("You do not have permission to change your room's map.", messages(ALICE).getLast());
    }

    @Test
    void rule6UnderSharedTheSameSeedMayBeSetByHandForSeveralSlots() {
        useSeedModes("maps: {seed-sharing: shared}");
        Room first = openWith(ALICE, BOB);
        rooms.mapSetGenerated(player(ALICE), 42L, 1);
        Room second = openWith(CAROL, DAVE);
        rooms.mapSetGenerated(player(CAROL), 42L, 1);
        drain();
        assertEquals(42, seedOf(first));
        assertEquals(42, seedOf(second));
        assertTrue(maps.isAvailable(first.arena()) && maps.isAvailable(second.arena()));
    }

    @Test
    void rule5ARotationOrRerollNeverChangesARunningMatchsMap() {
        useSeedModes("maps: {default: {mode: rotation, seeds: [42, 43, 44], rotate-every: 30m, difficulty: 1}}");
        Room room = generatedRoom(ALICE, BOB);
        String name = room.arena();
        assertEquals(42, seedOf(room));
        rooms.start(player(ALICE), null);
        assertTrue(room.isPlaying());
        Arena playing = maps.get(name).orElseThrow();

        now += 2 * HOUR;
        rooms.mapNext(player(ALICE));
        assertEquals("Alice asked for the next seed in the rotation. The map being played stays; it applies when the"
                + " map is generated next.", messages(BOB).getLast());
        for (MatchPhase phase : List.of(MatchPhase.SHOP, MatchPhase.PREP, MatchPhase.COMBAT)) {
            tickUntil(name, phase);
            assertSame(playing, maps.get(name).orElseThrow(), "the map is untouched in " + phase);
            assertFalse(generated.hasReplacement(name), "regenerate: match never regenerates between rounds");
        }
        assertEquals(42, seedOf(room));
        assertEquals(1, generated.inUse().size(), "nothing else is being generated");
        playToTheEnd(name);
        drain();
        // The next generation, after the match: one step on, though the timer ran out long ago.
        assertEquals(43, seedOf(room));
        assertTrue(maps.isAvailable(name));
    }

    @Test
    void mapNextAndRerollInTheLobbyGenerateTheRoomsMapAgainNow() {
        useSeedModes("maps: {default: {mode: rotation, seeds: [42, 43], rotate-every: 1d, difficulty: 1}}");
        Room room = generatedRoom(ALICE, BOB);
        assertEquals(42, seedOf(room));
        rooms.mapNext(player(ALICE));
        assertEquals("Alice asked for the next seed in the rotation: the room's map is generated again.",
                messages(BOB).get(messages(BOB).size() - 2));
        drain();
        assertEquals(43, seedOf(room));
        randoms.add(77L);
        rooms.mapReroll(player(ALICE));
        drain();
        assertEquals(77, seedOf(room));
        assertTrue(maps.isAvailable(room.arena()));

        Room handBuilt = openWith(CAROL, DAVE);
        rooms.mapNext(player(CAROL));
        assertEquals("The room does not play generated maps. Use /narena map set generated first.",
                messages(CAROL).getLast());
        assertEquals("dojo", handBuilt.arena());
    }

    @Test
    void perRoundRegenerationRunsInTheShopBreakAndTheRoundStartsOnTheNewMapOnlyOncePlaced() {
        useSeedModes("maps: {default: {regenerate: round, difficulty: 1}}");
        assertEquals(3, generated.cells().size(), "a spare cell for the next round's map");
        randoms.addAll(List.of(42L, 43L));
        Room room = generatedRoom(ALICE, BOB);
        String name = room.arena();
        assertEquals(42, seedOf(room));
        int slot = generated.slotNumber(name).orElseThrow();
        GridCell first = generated.cellOf(name).orElseThrow();
        rooms.start(player(ALICE), null);
        MatchView match = matches.match(name).orElseThrow();
        MobGoals goals = (MobGoals) Proxy.newProxyInstance(MobGoals.class.getClassLoader(),
                new Class<?>[] {MobGoals.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        MatchDirector.Spawner spawner = MobSpawner.factory(maps, () -> arenaWorld, plugin.getServer()::getPlayer,
                SpawnSettings.DEFAULTS, new ShadowStalker(StalkerSettings.DEFAULTS, goals, () -> 0),
                plugin.getLogger()).apply(match);

        tickUntil(name, MatchPhase.SHOP);
        assertTrue(generated.hasReplacement(name), "the next map is generated in the break");
        assertFalse(rooms.mapReady(match));
        assertEquals("Generating the next round's map from seed 43 at difficulty 1...", messages(ALICE).stream()
                .filter(text -> text.startsWith("Generating the next round's")).findFirst().orElseThrow());
        Arena current = maps.get(name).orElseThrow();
        // The shop stays open past its time while the map is placed, up to the wait limit.
        matches.tick();
        matches.tick();
        matches.tick();
        matches.tick();
        assertEquals(MatchPhase.SHOP, match.phase());
        assertTrue(match.isWaitingForMap());
        assertSame(current, maps.get(name).orElseThrow(), "the current map is untouched meanwhile");

        drain();
        assertFalse(generated.hasReplacement(name));
        assertTrue(rooms.mapReady(match));
        Arena next = maps.get(name).orElseThrow();
        assertNotEquals(first, next.cell(), "placed in another cell");
        assertEquals(slot, generated.slotNumber(name).orElseThrow(), "the slot number follows the map");
        assertEquals(43, seedOf(room));
        assertEquals(0, blocks.countSolid(generated.region(first)), "the old cell is cleared");
        assertEquals(next, assertInstanceOf(MobSpawner.Following.class, spawner).arena(),
                "the enemies spawn on the new map");

        matches.tick();
        assertEquals(MatchPhase.PREP, match.phase());
        for (UUID id : List.of(ALICE, BOB)) {
            assertAt(next.teamSpawns().get(match.teamOf(id) - 1), players.get(id).location);
        }
        assertEquals(List.of(next.cell()), generated.inUse(), "only the played cell is in use");
    }

    @Test
    void aNextMapNotPlacedInTimeIsDroppedAndTheRoundIsPlayedOnTheCurrentMap() {
        useSeedModes("maps: {default: {regenerate: round, difficulty: 1}}");
        randoms.addAll(List.of(42L, 43L));
        Room room = generatedRoom(ALICE, BOB);
        String name = room.arena();
        GridCell cell = generated.cellOf(name).orElseThrow();
        long placed = blocks.countSolid(generated.region(cell));
        rooms.start(player(ALICE), null);
        MatchView match = matches.match(name).orElseThrow();
        tickUntil(name, MatchPhase.SHOP);
        assertTrue(generated.hasReplacement(name));
        Arena current = maps.get(name).orElseThrow();
        for (int i = 0; i < SHOP.maxSeconds() + SHOP.mapWaitLimitSeconds() - 1; i++) {
            matches.tick();
        }
        assertEquals(MatchPhase.SHOP, match.phase());
        matches.tick();
        assertEquals(MatchPhase.PREP, match.phase(), "the wait limit ran out");
        assertTrue(messages(ALICE).contains("The next map is not ready: the round is played on " + name
                + " again after a reset."));
        assertFalse(generated.hasReplacement(name), "the half-placed map is dropped");
        assertSame(current, maps.get(name).orElseThrow());
        for (UUID id : List.of(ALICE, BOB)) {
            assertAt(current.teamSpawns().get(match.teamOf(id) - 1), players.get(id).location);
        }
        drain();
        assertEquals(List.of(cell), generated.inUse(), "only the played cell is in use");
        assertEquals(placed, blocks.countSolid(generated.region(cell)), "the current map was never touched");
        assertEquals(42, seedOf(room));
    }

    @Test
    void aNextMapThatFailsKeepsTheCurrentMapAsTheLastGoodSeed() {
        useSeedModes("maps: {default: {regenerate: round, difficulty: 1}}");
        randoms.addAll(List.of(42L, 666L));
        failing.add(666L);
        Room room = generatedRoom(ALICE, BOB);
        String name = room.arena();
        rooms.start(player(ALICE), null);
        MatchView match = matches.match(name).orElseThrow();
        Arena current = maps.get(name).orElseThrow();
        tickUntil(name, MatchPhase.SHOP);
        drain();
        assertFalse(generated.hasReplacement(name));
        assertTrue(rooms.mapReady(match));
        assertTrue(messages(BOB).contains("No playable map came from seed 666 in 0 tries, so the room plays the last"
                + " good seed, 42."), String.valueOf(messages(BOB)));
        tickUntil(name, MatchPhase.PREP);
        assertSame(current, maps.get(name).orElseThrow());
        assertEquals(42, seedOf(room));
        assertEquals(1, generated.inUse().size());
    }

    @Test
    void rule7UnderDistinctActiveSlotsNeverShareASeedDuringPerRoundRegeneration() {
        useSeedModes("maps: {default: {regenerate: round, difficulty: 1}}");
        // Each draw that another slot plays, or is generating, is skipped.
        randoms.addAll(List.of(42L, 42L, 43L, 43L, 44L));
        Room first = generatedRoom(ALICE, BOB);
        Room second = generatedRoom(CAROL, DAVE);
        assertEquals(List.of(42L, 43L), List.of(seedOf(first), seedOf(second)));
        rooms.start(player(ALICE), null);
        rooms.start(player(CAROL), null);
        tickUntil(first.arena(), MatchPhase.SHOP);
        assertEquals(MatchPhase.SHOP, matches.match(second.arena()).orElseThrow().phase());
        // One spare cell: the first break takes it, and the second keeps its map this round.
        assertTrue(generated.hasReplacement(first.arena()));
        assertFalse(generated.hasReplacement(second.arena()));
        drain();
        assertEquals(44, seedOf(first), "43, which the other slot plays, is skipped");
        assertEquals(43, seedOf(second));
        Set<Long> active = rooms.activeSeeds("", null);
        assertEquals(Set.of(43L, 44L), active);
        tickUntil(first.arena(), MatchPhase.COMBAT);
        tickUntil(first.arena(), MatchPhase.SHOP);
        drain();
        assertNotEquals(seedOf(first), seedOf(second));
    }

    @Test
    void underSharedPerRoundRegenerationKeepsActiveSlotsOnOneSeed() {
        useSeedModes("maps: {default: {regenerate: round, difficulty: 1}, seed-sharing: shared}");
        randoms.addAll(List.of(42L, 43L, 44L));
        Room first = generatedRoom(ALICE, BOB);
        Room second = generatedRoom(CAROL, DAVE);
        assertEquals(List.of(42L, 42L), List.of(seedOf(first), seedOf(second)), "the second slot joins the first");
        rooms.start(player(ALICE), null);
        rooms.start(player(CAROL), null);
        tickUntil(first.arena(), MatchPhase.SHOP);
        assertTrue(generated.hasReplacement(first.arena()), "generated again in the break");
        drain();
        assertEquals(List.of(42L, 42L), List.of(seedOf(first), seedOf(second)),
                "the other slot still plays 42, so the next round stays on it");
        assertEquals(Set.of(42L), rooms.activeSeeds("", null));
        tickUntil(first.arena(), MatchPhase.COMBAT);
        tickUntil(first.arena(), MatchPhase.SHOP);
        drain();
        assertEquals(seedOf(first), seedOf(second));
    }

    @Test
    void aMatchGeneratedAgainEachRoundKeepsEverySeedPlayedAndShowsThemInTheResult() {
        useSeedModes("maps: {default: {regenerate: round, difficulty: 1}}");
        randoms.addAll(List.of(42L, 43L, 44L, 45L, 46L));
        Room room = generatedRoom(ALICE, BOB);
        String name = room.arena();
        rooms.start(player(ALICE), null);
        MatchView match = matches.match(name).orElseThrow();
        for (int i = 0; i < 300 && matches.match(name).isPresent(); i++) {
            matches.tick();
            runNextTick();
            drain();
        }
        assertTrue(matches.match(name).isEmpty(), "the match closed");

        List<PlayedMap> played = match.maps();
        assertEquals(match.results().size(), played.stream().mapToInt(PlayedMap::rounds).sum(),
                "every round counts");
        assertEquals(new PlayedMap(name, new MapSeed(42, 1), 1, 1), played.getFirst());
        assertEquals(new PlayedMap(name, new MapSeed(43, 1), 2, 1), played.get(1));
        for (PlayedMap map : played) {
            MapSeed seed = map.generated().orElseThrow();
            assertTrue(messages(BOB).contains("Rounds " + map.firstRound() + " to " + map.lastRound() + ": seed "
                    + seed.seed() + " at difficulty 1. Play it again with /narena map set generated " + seed.seed()
                    + " 1."), String.valueOf(messages(BOB)));
        }
        List<MatchRecord> records = new java.util.ArrayList<>();
        new StatsTracker(records::add, plugin.getServer()::getPlayer, java.time.Instant::now)
                .onMatchEnd(events(MatchEndEvent.class).getLast());
        assertEquals(played.stream().map(map -> MatchRecord.Played.generated(map.seed().seed(), 1, map.firstRound(),
                map.rounds())).toList(), records.getFirst().match().maps());
    }

    @Test
    void underSharedALoneRoomGetsANewSeedEachRound() {
        useSeedModes("maps: {default: {regenerate: round, difficulty: 1}, seed-sharing: shared}");
        randoms.addAll(List.of(42L, 43L));
        Room room = generatedRoom(ALICE, BOB);
        assertEquals(42, seedOf(room));
        rooms.start(player(ALICE), null);
        tickUntil(room.arena(), MatchPhase.SHOP);
        drain();
        assertEquals(43, seedOf(room));
    }

    @Test
    void anAdminReproducesALayoutInAFreeSlotCellAndFreesItAgain() {
        useSeedModes("maps: {default: {difficulty: 1}}");
        List<String> told = new java.util.ArrayList<>();
        org.bukkit.command.CommandSender admin = (org.bukkit.command.CommandSender) Proxy.newProxyInstance(
                org.bukkit.command.ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[] {org.bukkit.command.ConsoleCommandSender.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "sendMessage" -> {
                                told.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                        .plainText().serialize((net.kyori.adventure.text.Component) args[0]));
                                yield null;
                            }
                            case "getName" -> "CONSOLE";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        AdminMaps admins = new AdminMaps(generated, rooms::hasSpareCell, () -> 1, plugin.getLogger());
        Room playing = generatedRoom(ALICE, BOB);
        admins.generate(admin, 42, null, null);
        assertEquals("Generating seed 42 at difficulty 1 for 2 teams...", told.getLast());
        String layout = admins.map().orElseThrow();
        admins.generate(admin, 7, null, null);
        assertEquals("A layout is placed already, as " + layout + ". Clear it first with /narena admin generate free.",
                told.getLast());
        drain();
        Arena placed = maps.get(layout).orElseThrow();
        assertTrue(told.getLast().startsWith("Seed 42 at difficulty 1 for 2 teams is placed in slot cell "
                + placed.cell().describe()), told.getLast());
        assertFalse(maps.isHeld(layout));
        assertNotEquals(playing.arena(), layout);
        assertEquals(Set.of(seedOf(playing)), rooms.activeSeeds("", null), "an admin's layout is no slot's seed");

        // Both cells are in use: a room waiting for one comes before an admin's layout.
        Room waiting = openWith(CAROL, DAVE);
        rooms.mapSetGenerated(player(CAROL), null, null);
        assertFalse(rooms.hasLease(waiting) && generated.cellOf(waiting.arena()).isPresent());
        admins.free(admin);
        assertEquals("The layout is being cleared and its slot cell freed.", told.getLast());
        drain();
        assertTrue(maps.isAvailable(waiting.arena()), "the freed cell goes to the room");
        admins.generate(admin, 42, 3, 4);
        assertEquals("No slot cell is free for a layout now. Try again once a room's map is cleared.", told.getLast());
        admins.free(admin);
        assertEquals("No layout is placed.", told.getLast());
        admins.generate(admin, 42, 9, null);
        assertEquals("The difficulty must be from 1 to 5.", told.getLast());
        admins.generate(admin, 42, 2, 3);
        assertEquals("A layout is generated for 2 or 4 teams.", told.getLast());
    }
}
