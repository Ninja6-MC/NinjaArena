package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.room.Room;
import com.ninja6.ninjaarena.room.RoomSettings;
import com.ninja6.ninjaarena.room.RoomSettings.Privacy;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

class RoomPadsTest {
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID CAROL = new UUID(0, 3);
    private static final UUID DAVE = new UUID(0, 4);

    private final List<FakeEntity> spawned = new ArrayList<>();
    private final Map<UUID, FakePlayer> online = new LinkedHashMap<>();
    private final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[] {World.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getEntities" -> spawned.stream().filter(e -> !e.removed).map(e -> e.entity).toList();
                case "getPlayers" -> online.values().stream().map(p -> p.proxy).toList();
                case "spawn" -> spawn((Location) args[0], args[2]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    private final List<Room> open = new ArrayList<>();
    private final Set<UUID> eligible = new HashSet<>();
    private final Set<UUID> bedrock = new HashSet<>();
    private final List<String> triggered = new ArrayList<>();
    private final List<String> added = new ArrayList<>();
    private final List<String> released = new ArrayList<>();
    private final BoardTickets tickets = new BoardTickets((x, z) -> added.add(x + "," + z),
            (x, z) -> released.add(x + "," + z));
    private LobbyLayout layout = GeneratedLobby.plan(4, 7, 1).layout();
    private boolean building;
    private long now;

    private RoomPads pads() {
        return pads(PadSettings.DEFAULTS);
    }

    private RoomPads pads(PadSettings settings) {
        return new RoomPads(world, () -> layout, () -> building, new RoomPads.PadRooms() {
            @Override public List<Room> rooms() {
                return List.copyOf(open);
            }

            @Override public String name(UUID player) {
                return player.equals(ALICE) ? "Alice" : player.equals(CAROL) ? "Carol" : "Dave";
            }

            @Override public boolean eligible(UUID player) {
                return eligible.contains(player);
            }

            @Override public void trigger(Player player, Room room) {
                triggered.add(player.getName() + "->" + name(room.host()));
            }
        }, (at, ready) -> ready.run(), tickets, bedrock::contains, settings, () -> now, message -> { });
    }

    private static PadSettings fog(int distance, int perBlock, int max, int bedrockMax) {
        PadSettings d = PadSettings.DEFAULTS;
        return new PadSettings(true, d.joinSeconds(), true, distance, d.activeSeconds(), d.fogIntervalTicks(),
                d.fogSize(), perBlock, max, bedrockMax, d.colours());
    }

    private Room room(UUID host, Privacy privacy) {
        Room room = new Room(host, "garden", new RoomSettings(privacy, 2, 2, 0));
        open.add(room);
        return room;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ---- Fakes ----

    @SuppressWarnings("unchecked")
    private Entity spawn(Location at, Object initializer) {
        FakeEntity fake = new FakeEntity(at);
        spawned.add(fake);
        ((Consumer<Entity>) initializer).accept(fake.entity);
        return fake.entity;
    }

    private final class FakeEntity {
        final Location at;
        final Map<String, Object> properties = new HashMap<>();
        final Map<Object, Object> data = new HashMap<>();
        boolean removed;
        final PersistentDataContainer pdc = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[] {PersistentDataContainer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> { data.put(args[0], args[2]); yield null; }
                    case "get" -> data.get(args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final Entity entity;

        FakeEntity(Location at) {
            this.at = at;
            entity = (Entity) Proxy.newProxyInstance(TextDisplay.class.getClassLoader(),
                    new Class<?>[] {TextDisplay.class}, (proxy, method, args) -> {
                        if (method.getName().startsWith("set") || method.getName().equals("text")) {
                            properties.put(method.getName(), args[0]);
                            return null;
                        }
                        return switch (method.getName()) {
                            case "getWorld" -> world;
                            case "isValid" -> !removed;
                            case "getPersistentDataContainer" -> pdc;
                            case "remove" -> { removed = true; yield null; }
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        String text() {
            return plain((Component) properties.get("text"));
        }
    }

    private List<FakeEntity> labels() {
        return spawned.stream().filter(e -> !e.removed).toList();
    }

    private final class FakePlayer {
        final UUID id;
        final String name;
        Location at;
        final List<String> titles = new ArrayList<>();
        int cleared;
        int particles;
        final List<Object[]> calls = new ArrayList<>();
        final Player proxy;

        FakePlayer(UUID id, String name) {
            this.id = id;
            this.name = name;
            proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> name;
                        case "getLocation" -> at.clone();
                        case "showTitle" -> {
                            Title title = (Title) args[0];
                            titles.add(plain(title.title()) + "|" + plain(title.subtitle()));
                            yield null;
                        }
                        case "clearTitle" -> { cleared++; yield null; }
                        case "spawnParticle" -> {
                            calls.add(args.clone());
                            particles += (Integer) args[4];
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private FakePlayer player(UUID id, String name) {
        FakePlayer player = new FakePlayer(id, name);
        player.at = new Location(world, 0.5, GeneratedLobby.FLOOR_Y + 1, 0.5);
        online.put(id, player);
        eligible.add(id);
        return player;
    }

    /** Puts the player on the pad's plate, dx and dz blocks from its north-west corner. */
    private void stand(FakePlayer player, int pad, int dx, int dy, int dz) {
        ArenaBounds plate = layout.pads().get(pad).plate();
        player.at = new Location(world, plate.minX() + dx + 0.5, plate.minY() + 1 + dy, plate.minZ() + dz + 0.5,
                player.at.getYaw() + 30, 0);
    }

    private void offPlate(FakePlayer player) {
        player.at = new Location(world, 0.5, GeneratedLobby.FLOOR_Y + 1, 0.5);
    }

    private void at(RoomPads pads, long millis) {
        now = millis;
        pads.tick();
    }

    // ---- Countdown ----

    @Test
    void standingFiveSecondsTriggersOnceWithATitleCountdown() {
        room(ALICE, Privacy.PUBLIC);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        stand(bob, 0, 2, 0, 2);
        at(pads, 0);
        assertEquals(List.of("5|Stay on the pad to join the room of Alice"), bob.titles);
        at(pads, 1000);
        at(pads, 4999);
        assertTrue(triggered.isEmpty());
        assertEquals("1|Stay on the pad to join the room of Alice", bob.titles.getLast());
        at(pads, 5000);
        assertEquals(List.of("Bob->Alice"), triggered);
        assertEquals(1, bob.cleared);
        at(pads, 20_000);
        assertEquals(1, triggered.size(), "nothing more until they step off");
    }

    @Test
    void steppingOffCancelsAndSteppingBackOnStartsFromTheFullTime() {
        room(ALICE, Privacy.PUBLIC);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        stand(bob, 0, 0, 0, 0);
        at(pads, 0);
        at(pads, 4000);
        offPlate(bob);
        at(pads, 4100);
        assertEquals(1, bob.cleared);
        stand(bob, 0, 4, 0, 4);
        at(pads, 4200);
        at(pads, 9100);
        assertTrue(triggered.isEmpty());
        at(pads, 9200);
        assertEquals(List.of("Bob->Alice"), triggered);
        offPlate(bob);
        at(pads, 9300);
        stand(bob, 0, 1, 0, 1);
        at(pads, 9400);
        at(pads, 14_400);
        assertEquals(2, triggered.size(), "stepping off and on again counts afresh");
    }

    @Test
    void movingTurningAndJumpingOnThePlateKeepTheCountdown() {
        room(ALICE, Privacy.PUBLIC);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        stand(bob, 0, 0, 0, 0);
        at(pads, 0);
        stand(bob, 0, 4, 1, 0);
        at(pads, 1500);
        stand(bob, 0, 4, 0, 4);
        at(pads, 3000);
        stand(bob, 0, 0, 2, 4);
        at(pads, 4500);
        stand(bob, 0, 2, 0, 2);
        at(pads, 5000);
        assertEquals(List.of("Bob->Alice"), triggered);
        assertEquals(1, bob.cleared);
    }

    @Test
    void movingToAnotherPadStartsAgain() {
        room(ALICE, Privacy.PUBLIC);
        room(CAROL, Privacy.PUBLIC);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        stand(bob, 0, 0, 0, 0);
        at(pads, 0);
        stand(bob, 1, 0, 0, 0);
        at(pads, 3000);
        at(pads, 7999);
        assertTrue(triggered.isEmpty());
        at(pads, 8000);
        assertEquals(List.of("Bob->Carol"), triggered);
    }

    @Test
    void subtitleSaysWhatThePadDoesForEachPrivacy() {
        room(ALICE, Privacy.ASK);
        room(CAROL, Privacy.INVITE);
        Room invited = room(DAVE, Privacy.INVITE);
        invited.invite(BOB);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        stand(bob, 0, 0, 0, 0);
        at(pads, 0);
        stand(bob, 1, 0, 0, 0);
        at(pads, 100);
        stand(bob, 2, 0, 0, 0);
        at(pads, 200);
        assertEquals(List.of("5|Stay on the pad to ask to join the room of Alice",
                "5|The room of Carol is invite only", "5|Stay on the pad to join the room of Dave"), bob.titles);
    }

    @Test
    void ineligiblePlayersDoNotTriggerUntilTheyAreEligible() {
        room(ALICE, Privacy.PUBLIC);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        eligible.remove(BOB);
        stand(bob, 0, 0, 0, 0);
        at(pads, 0);
        at(pads, 10_000);
        assertTrue(triggered.isEmpty());
        assertTrue(bob.titles.isEmpty());
        eligible.add(BOB);
        at(pads, 11_000);
        at(pads, 15_999);
        assertTrue(triggered.isEmpty());
        at(pads, 16_000);
        assertEquals(List.of("Bob->Alice"), triggered);
    }

    @Test
    void onlyLobbyMembersInNoRoomMatchOrSpectatingAreEligible() {
        Set<UUID> members = Set.of(BOB, CAROL, DAVE);
        Set<UUID> inRoom = Set.of(CAROL);
        Set<UUID> inMatch = Set.of(ALICE);
        Set<UUID> spectating = Set.of(DAVE);
        assertTrue(RoomPads.eligible(BOB, members::contains, inRoom::contains, inMatch::contains,
                spectating::contains));
        // Alice is in a match, in its shop phase in the lobby world: no lobby member.
        assertFalse(RoomPads.eligible(ALICE, members::contains, inRoom::contains, inMatch::contains,
                spectating::contains));
        assertFalse(RoomPads.eligible(ALICE, id -> true, inRoom::contains, inMatch::contains, spectating::contains));
        assertFalse(RoomPads.eligible(CAROL, members::contains, inRoom::contains, inMatch::contains,
                spectating::contains));
        assertFalse(RoomPads.eligible(DAVE, members::contains, inRoom::contains, inMatch::contains,
                spectating::contains));
        assertFalse(RoomPads.eligible(new UUID(0, 9), members::contains, inRoom::contains, inMatch::contains,
                spectating::contains));
    }

    @Test
    void freePadsAndPadsOfRoomsInAMatchDoNothing() {
        Room alice = room(ALICE, Privacy.PUBLIC);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        stand(bob, 3, 0, 0, 0);
        at(pads, 0);
        at(pads, 10_000);
        assertTrue(bob.titles.isEmpty());
        alice.setPlaying(true);
        stand(bob, 0, 0, 0, 0);
        at(pads, 11_000);
        at(pads, 20_000);
        assertTrue(triggered.isEmpty());
        assertTrue(bob.titles.isEmpty());
        alice.setPlaying(false);
        at(pads, 21_000);
        at(pads, 26_000);
        assertEquals(List.of("Bob->Alice"), triggered);
    }

    @Test
    void fullRoomsDoNotCountDownUntilThereIsSpace() {
        Room alice = new Room(ALICE, "garden", new RoomSettings(Privacy.PUBLIC, 2, 2, 1));
        open.add(alice);
        alice.invite(CAROL);
        alice.join(CAROL);
        assertTrue(alice.isFull());
        alice.invite(BOB);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        stand(bob, 0, 0, 0, 0);
        at(pads, 0);
        at(pads, 10_000);
        assertTrue(bob.titles.isEmpty(), "a full room takes nobody, an invited player included");
        assertTrue(triggered.isEmpty());
        alice.leave(CAROL, id -> true);
        at(pads, 11_000);
        assertEquals("5|Stay on the pad to join the room of Alice", bob.titles.getLast());
        at(pads, 16_000);
        assertEquals(List.of("Bob->Alice"), triggered, "the join path makes the final check");
    }

    // ---- Assignment ----

    @Test
    void roomsTakeTheFirstFreePadAndClosingFreesItAndItsLabel() {
        Room alice = room(ALICE, Privacy.PUBLIC);
        Room carol = room(CAROL, Privacy.ASK);
        RoomPads pads = pads();
        pads.tick();
        assertEquals(0, pads.padOf(alice).getAsInt());
        assertEquals(1, pads.padOf(carol).getAsInt());
        assertEquals(2, labels().size());
        open.remove(alice);
        pads.tick();
        assertTrue(pads.padOf(alice).isEmpty());
        assertNull(pads.roomAt(0));
        assertEquals(1, labels().size());
        assertEquals(added.size(), released.size() + 1);
        Room dave = room(DAVE, Privacy.PUBLIC);
        pads.tick();
        assertEquals(0, pads.padOf(dave).getAsInt());
        assertEquals(2, labels().size());
    }

    @Test
    void roomsBeyondThePadCountHaveNoneUntilOneIsFree() {
        layout = GeneratedLobby.plan(1, 7, 1).layout();
        Room alice = room(ALICE, Privacy.PUBLIC);
        Room carol = room(CAROL, Privacy.PUBLIC);
        RoomPads pads = pads();
        pads.tick();
        assertSame(alice, pads.roomAt(0));
        assertTrue(pads.padOf(carol).isEmpty());
        open.remove(alice);
        pads.tick();
        assertSame(carol, pads.roomAt(0));
        assertEquals(1, labels().size());
        assertTrue(labels().getFirst().text().startsWith("Carol"));
    }

    @Test
    void aLobbyBuiltAgainMovesLabelsAndFreesPadsThatAreGone() {
        Room alice = room(ALICE, Privacy.PUBLIC);
        Room carol = room(CAROL, Privacy.PUBLIC);
        Room dave = room(DAVE, Privacy.PUBLIC);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        pads.tick();
        assertEquals(3, labels().size());
        building = true;
        layout = GeneratedLobby.plan(2, 9, 1).layout();
        stand(bob, 0, 0, 0, 0);
        pads.tick();
        assertTrue(labels().isEmpty(), "no labels while the lobby is built");
        assertTrue(pads.padOf(dave).isEmpty());
        at(pads, 10_000);
        assertTrue(triggered.isEmpty(), "no countdown while the lobby is built");
        building = false;
        at(pads, 10_100);
        assertEquals(2, labels().size());
        assertEquals(layout.pads().get(0).label().x(), labels().getFirst().at.getX());
        assertSame(alice, pads.roomAt(0));
        assertSame(carol, pads.roomAt(1));
        at(pads, 15_100);
        assertEquals(List.of("Bob->Alice"), triggered);
    }

    @Test
    void theObsidianLobbyHasNoPadsSoNothingHappens() {
        layout = LobbyLayout.bundled();
        Room alice = room(ALICE, Privacy.PUBLIC);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        pads.markActive(BOB);
        for (int i = 0; i < 100; i++) {
            at(pads, i * 1000L);
            pads.update();
            pads.drawFog();
        }
        assertTrue(pads.padOf(alice).isEmpty());
        assertTrue(spawned.isEmpty());
        assertTrue(added.isEmpty());
        assertTrue(triggered.isEmpty());
        assertTrue(bob.titles.isEmpty());
        assertEquals(0, bob.particles);
    }

    @Test
    void disableRemovesLabelsAndTheirTicketsAndClearsTitles() {
        room(ALICE, Privacy.PUBLIC);
        room(CAROL, Privacy.PUBLIC);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        stand(bob, 0, 0, 0, 0);
        at(pads, 0);
        FakeEntity stray = new FakeEntity(new Location(world, 0, 70, 0));
        stray.data.put(RoomPads.KEY, 3);
        spawned.add(stray);
        pads.close();
        assertTrue(labels().isEmpty());
        assertEquals(added.size(), released.size());
        assertEquals(1, bob.cleared);
        pads.tick();
        assertTrue(labels().isEmpty());
    }

    @Test
    void updateRefreshesTextReplacesLostLabelsAndSweepsDuplicates() {
        Room alice = room(ALICE, Privacy.PUBLIC);
        RoomPads pads = pads();
        pads.tick();
        FakeEntity label = labels().getFirst();
        assertEquals("Alice\n1/2 · Open", label.text());
        alice.setPlaying(true);
        pads.update();
        assertEquals("Alice\n1/2 · In match", label.text());
        label.removed = true;
        FakeEntity duplicate = new FakeEntity(label.at);
        duplicate.data.put(RoomPads.KEY, 0);
        spawned.add(duplicate);
        pads.update();
        assertTrue(duplicate.removed);
        assertEquals(1, labels().size());
        assertEquals("Alice\n1/2 · In match", labels().getFirst().text());
    }

    // ---- Labels ----

    @Test
    void labelNamesTheHostCountAndStateInWords() {
        assertEquals("Alice\n1/4 · Open", plain(RoomPads.label("Alice", 1, 4, PadState.OPEN)));
        assertEquals("Alice\n2/4 · Ask to join", plain(RoomPads.label("Alice", 2, 4, PadState.ASK)));
        assertEquals("Alice\n3/4 · Invite only", plain(RoomPads.label("Alice", 3, 4, PadState.INVITE)));
        assertEquals("Alice\n4/4 · Full", plain(RoomPads.label("Alice", 4, 4, PadState.FULL)));
        assertEquals("Alice\n4/4 · In match", plain(RoomPads.label("Alice", 4, 4, PadState.PLAYING)));
    }

    @Test
    void stateFollowsMatchThenFullThenPrivacy() {
        Room room = new Room(ALICE, "garden", new RoomSettings(Privacy.ASK, 2, 2, 1));
        assertEquals(PadState.ASK, PadState.of(room));
        room.setSettings(room.settings().withPrivacy(Privacy.PUBLIC));
        assertEquals(PadState.OPEN, PadState.of(room));
        room.setSettings(room.settings().withPrivacy(Privacy.INVITE));
        assertEquals(PadState.INVITE, PadState.of(room));
        room.invite(BOB);
        room.join(BOB);
        assertEquals(PadState.FULL, PadState.of(room));
        room.setPlaying(true);
        assertEquals(PadState.PLAYING, PadState.of(room));
    }

    // ---- Fog ----

    @Test
    void fogGoesOnlyToRecentlyActiveViewers() {
        room(ALICE, Privacy.PUBLIC);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        FakePlayer dave = player(DAVE, "Dave");
        stand(bob, 0, 2, 0, 2);
        stand(dave, 0, 2, 0, 2);
        pads.tick();
        pads.drawFog();
        assertEquals(0, bob.particles, "no activity yet");
        pads.markActive(BOB);
        now = 30_000;
        pads.drawFog();
        assertEquals(144, bob.particles, "six particles on each of the frame's 24 blocks");
        assertEquals(24, bob.calls.size(), "one call a block");
        assertEquals(0, dave.particles);
        now = 30_001;
        pads.drawFog();
        assertEquals(144, bob.particles, "idle longer than active-seconds");
    }

    @Test
    void theDefaultsAreTheTrialValues() {
        PadSettings d = PadSettings.DEFAULTS;
        assertEquals(7, d.fogIntervalTicks(), "a draw every 0.35 seconds");
        assertEquals(2.0, d.fogSize());
        assertEquals(6, d.perBlock());
        assertEquals(1.15, RoomPads.HEIGHT);
        assertEquals(0.25, RoomPads.SPREAD);
        assertEquals(0.04, RoomPads.SPREAD_Y);
    }

    /** Checks every call of a draw against the trial: one per frame block, centred, with its count and spread. */
    private static void assertTrialCalls(FakePlayer viewer, LobbyPad pad, int perBlock, int colour) {
        List<int[]> ring = pad.ring();
        assertEquals(ring.size(), viewer.calls.size());
        Set<String> blocks = new HashSet<>();
        for (Object[] call : viewer.calls) {
            assertEquals(org.bukkit.Particle.DUST, call[0]);
            double x = (Double) call[1];
            double y = (Double) call[2];
            double z = (Double) call[3];
            assertEquals(0.5, x - Math.floor(x), 1e-9, "centred on the block");
            assertEquals(0.5, z - Math.floor(z), 1e-9, "centred on the block");
            assertEquals(pad.frame().minY() + 1.15, y, 1e-9, "about 1.15 above the floor");
            assertEquals(perBlock, call[4]);
            assertEquals(0.25, call[5]);
            assertEquals(0.04, call[6]);
            assertEquals(0.25, call[7]);
            assertEquals(0.0, call[8], "speed 0");
            org.bukkit.Particle.DustOptions dust = (org.bukkit.Particle.DustOptions) call[9];
            assertEquals(2.0f, dust.getSize());
            assertEquals(colour, dust.getColor().asRGB());
            assertEquals(Boolean.TRUE, call[10], "forced, as in the trial");
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            assertTrue(pad.frame().contains(bx, pad.frame().minY(), bz), "on the frame");
            assertFalse(pad.plate().contains(bx, pad.plate().minY(), bz), "not on the plate");
            assertTrue(blocks.add(bx + "," + bz), "one call a block");
        }
        Set<String> expected = new HashSet<>();
        ring.forEach(b -> expected.add(b[0] + "," + b[2]));
        assertEquals(expected, blocks, "every block of the frame's ring");
    }

    @Test
    void fogLandsOnTheFrameRingOfAGeneratedPadAsOneCallABlock() {
        room(ALICE, Privacy.PUBLIC);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        stand(bob, 0, 2, 0, 2);
        pads.tick();
        assertEquals(144, pads.drawFor(bob.proxy, pads.cap(BOB)));
        assertTrialCalls(bob, layout.pads().get(0), 6, PadSettings.DEFAULTS.colour(PadState.OPEN));
    }

    @Test
    void fogLandsOnTheFrameRingOfAPadInTheObsidianBuild() {
        // The bundled obsidian build has no pads; a pad placed in it, with a frame two blocks
        // wider than its plate all round, still gets its fog on the frame's outer ring.
        LobbyLayout obsidian = LobbyLayout.bundled();
        ArenaBounds area = obsidian.roomArea();
        int y = area.minY() - 1;
        ArenaBounds frame = new ArenaBounds(area.minX(), y, area.minZ(), area.minX() + 6, y, area.minZ() + 6);
        ArenaBounds plate = new ArenaBounds(area.minX() + 2, y, area.minZ() + 2, area.minX() + 4, y, area.minZ() + 4);
        LobbyPad pad = new LobbyPad(0, LobbyPad.Side.NORTH, frame, plate,
                new com.ninja6.ninjaarena.arena.ArenaPoint(area.minX() + 3.5, y + 3, area.minZ() + 3.5, 0, 0));
        layout = new LobbyLayout(obsidian.build(), obsidian.bounds(), obsidian.spawn(), obsidian.roomArea(),
                obsidian.shopArea(), obsidian.boards(), List.of(pad));
        room(ALICE, Privacy.ASK);
        RoomPads pads = pads();
        FakePlayer bob = player(BOB, "Bob");
        stand(bob, 0, 1, 0, 1);
        pads.tick();
        assertEquals(144, pads.drawFor(bob.proxy, pads.cap(BOB)));
        assertTrialCalls(bob, pad, 6, PadSettings.DEFAULTS.colour(PadState.ASK));
    }

    @Test
    void theJavaCapLeavesOnePadWholeAndBoundsACrowdedLobby() {
        room(ALICE, Privacy.PUBLIC);
        RoomPads one = pads(fog(64, 6, PadSettings.DEFAULT_MAX_PARTICLES, PadSettings.DEFAULT_BEDROCK_MAX_PARTICLES));
        FakePlayer viewer = player(new UUID(0, 9), "Viewer");
        one.tick();
        assertEquals(144, one.drawFor(viewer.proxy, one.cap(viewer.id)), "a single pad is not thinned");
        assertTrue(viewer.calls.stream().allMatch(c -> (Integer) c[4] == 6));
        room(CAROL, Privacy.ASK);
        room(DAVE, Privacy.INVITE);
        Room fourth = room(BOB, Privacy.PUBLIC);
        fourth.setPlaying(true);
        RoomPads crowded = pads(fog(64, 6, PadSettings.DEFAULT_MAX_PARTICLES,
                PadSettings.DEFAULT_BEDROCK_MAX_PARTICLES));
        crowded.tick();
        viewer.calls.clear();
        assertEquals(432, crowded.drawFor(viewer.proxy, crowded.cap(viewer.id)), "three whole pads, then the cap");
        assertEquals(72, viewer.calls.size(), "72 packets: 24 a pad");
        assertTrue(viewer.calls.stream().allMatch(c -> (Integer) c[4] == 6));
        RoomPads near = pads(fog(4, 6, 1152, 288));
        near.tick();
        stand(viewer, 0, 2, 0, 2);
        assertEquals(144, near.drawFor(viewer.proxy, 1152), "only the pad underfoot is within 4 blocks");
        assertEquals(0, near.drawFor(viewer.proxy, 0));
    }

    @Test
    void theBedrockCapThinsTheNearestPadEvenly() {
        room(ALICE, Privacy.PUBLIC);
        room(CAROL, Privacy.ASK);
        RoomPads pads = pads(fog(64, 6, PadSettings.DEFAULT_MAX_PARTICLES, PadSettings.DEFAULT_BEDROCK_MAX_PARTICLES));
        FakePlayer viewer = player(new UUID(0, 9), "Viewer");
        bedrock.add(viewer.id);
        stand(viewer, 0, 2, 0, 2);
        pads.tick();
        assertEquals(48, pads.cap(viewer.id));
        assertEquals(48, pads.drawFor(viewer.proxy, pads.cap(viewer.id)));
        assertTrialCalls(viewer, layout.pads().get(0), 2, PadSettings.DEFAULTS.colour(PadState.OPEN));
        RoomPads none = pads(fog(64, 6, PadSettings.DEFAULT_MAX_PARTICLES, 0));
        none.tick();
        assertEquals(0, none.drawFor(viewer.proxy, none.cap(viewer.id)));
    }

    @Test
    void aCappedDrawThinsTheRingOnAllFourSidesAndRotates() {
        LobbyPad pad = layout.pads().get(0);
        List<int[]> ring = pad.ring();
        ArenaBounds frame = pad.frame();
        for (int[] budget : new int[][] {{6, 48}, {6, 100}, {1, 8}, {3, 5}, {12, 24}}) {
            int[] counts = RoomPads.spread(ring.size(), budget[0], budget[1], 0);
            assertEquals(Math.min(budget[1], ring.size() * budget[0]), java.util.Arrays.stream(counts).sum());
            List<int[]> lit = new ArrayList<>();
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0) {
                    lit.add(ring.get(i));
                }
            }
            assertTrue(lit.stream().anyMatch(b -> b[2] == frame.minZ()), "north");
            assertTrue(lit.stream().anyMatch(b -> b[0] == frame.maxX()), "east");
            assertTrue(lit.stream().anyMatch(b -> b[2] == frame.maxZ()), "south");
            assertTrue(lit.stream().anyMatch(b -> b[0] == frame.minX()), "west");
        }
        int[] bedrockDraw = RoomPads.spread(ring.size(), 6, 48, 0);
        assertTrue(java.util.Arrays.stream(bedrockDraw).allMatch(c -> c == 2), "48 is two on every block");
        Set<Integer> covered = new HashSet<>();
        for (int draw = 0; draw < 6; draw++) {
            int[] counts = RoomPads.spread(ring.size(), 6, 4, draw);
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0) {
                    covered.add(i);
                }
            }
        }
        assertEquals(24, covered.size(), "over a few draws the whole ring gets fog");
        assertTrue(java.util.Arrays.stream(RoomPads.spread(ring.size(), 6, 1000, 0)).allMatch(c -> c == 6));
        assertEquals(0, java.util.Arrays.stream(RoomPads.spread(ring.size(), 6, 0, 0)).sum());
    }

    @Test
    void everyCappedDrawIsEvenAllRound() {
        LobbyPad pad = layout.pads().get(0);
        List<int[]> ring = pad.ring();
        ArenaBounds frame = pad.frame();
        for (int perBlock = 1; perBlock <= PadSettings.MAX_PER_BLOCK; perBlock++) {
            for (int budget = 1; budget <= ring.size() * perBlock + 4; budget++) {
                for (int draw = 0; draw < ring.size(); draw++) {
                    int[] counts = RoomPads.spread(ring.size(), perBlock, budget, draw);
                    String at = perBlock + "/" + budget + "/" + draw;
                    assertEquals(Math.min(budget, ring.size() * perBlock), java.util.Arrays.stream(counts).sum(), at);
                    int max = java.util.Arrays.stream(counts).max().orElseThrow();
                    int min = java.util.Arrays.stream(counts).min().orElseThrow();
                    assertTrue(max - min <= 1, at);
                    assertTrue(max <= perBlock, at);
                    if (budget >= 4) {
                        List<int[]> lit = new ArrayList<>();
                        for (int i = 0; i < counts.length; i++) {
                            if (counts[i] > 0) {
                                lit.add(ring.get(i));
                            }
                        }
                        assertTrue(lit.stream().anyMatch(b -> b[2] == frame.minZ()), "north " + at);
                        assertTrue(lit.stream().anyMatch(b -> b[0] == frame.maxX()), "east " + at);
                        assertTrue(lit.stream().anyMatch(b -> b[2] == frame.maxZ()), "south " + at);
                        assertTrue(lit.stream().anyMatch(b -> b[0] == frame.minX()), "west " + at);
                    }
                }
            }
        }
    }

    @Test
    void settingsReadDefaultsAndClampOutOfRangeValues() {
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        assertEquals(PadSettings.DEFAULTS, PadSettings.from(config.getConfigurationSection("lobby.pads"), m -> { }));
        config.set("lobby.pads.join-seconds", 99);
        config.set("lobby.pads.fog.max-particles", 2);
        config.set("lobby.pads.fog.size", 9.0);
        config.set("lobby.pads.fog.per-block", 20);
        config.set("lobby.pads.fog.colours.open", "#123456");
        config.set("lobby.pads.fog.colours.full", "red");
        List<String> warnings = new ArrayList<>();
        PadSettings read = PadSettings.from(config.getConfigurationSection("lobby.pads"), warnings::add);
        assertEquals(30, read.joinSeconds());
        assertEquals(PadSettings.MIN_MAX_PARTICLES, read.maxParticles());
        assertEquals(PadSettings.MAX_SIZE, read.fogSize());
        assertEquals(PadSettings.MAX_PER_BLOCK, read.perBlock());
        assertEquals(0x123456, read.colour(PadState.OPEN));
        assertEquals(0xFF5555, read.colour(PadState.FULL));
        assertEquals(5, warnings.size(), warnings.toString());
    }

    @Test
    void bundledConfigHasTheDefaults() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        try (var in = new java.io.InputStreamReader(RoomPadsTest.class.getClassLoader().getResourceAsStream("config.yml"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            config.load(in);
        }
        List<String> warnings = new ArrayList<>();
        assertEquals(PadSettings.DEFAULTS, PadSettings.from(config.getConfigurationSection(PadSettings.SECTION),
                warnings::add));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void aServerConfigWithoutTheSectionReadsTheBundledDefaultsWithoutWarnings() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration bundled = new org.bukkit.configuration.file.YamlConfiguration();
        try (var in = new java.io.InputStreamReader(RoomPadsTest.class.getClassLoader().getResourceAsStream("config.yml"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            bundled.load(in);
        }
        org.bukkit.configuration.file.YamlConfiguration server = new org.bukkit.configuration.file.YamlConfiguration();
        server.set("arenas.max-active", 4);
        server.setDefaults(bundled);
        List<String> warnings = new ArrayList<>();
        assertEquals(PadSettings.DEFAULTS, PadSettings.from(server.getConfigurationSection(PadSettings.SECTION),
                warnings::add));
        assertTrue(warnings.isEmpty(), warnings.toString());
        server.set("lobby.pads.fog.colours.ask", "nope");
        warnings.clear();
        PadSettings read = PadSettings.from(server.getConfigurationSection(PadSettings.SECTION), warnings::add);
        assertEquals(PadSettings.DEFAULTS.colour(PadState.ASK), read.colour(PadState.ASK));
        assertEquals(List.of("lobby.pads.fog.colours.ask must be a colour like \"#55FF55\"; using the default."),
                warnings);
    }
}
