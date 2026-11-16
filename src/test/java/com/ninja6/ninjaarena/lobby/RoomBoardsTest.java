package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.room.RoomSettings.Privacy;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

class RoomBoardsTest {
    private final List<FakeEntity> spawned = new ArrayList<>();
    private final List<Player> opened = new ArrayList<>();
    private final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[] {World.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getEntities" -> spawned.stream().filter(e -> !e.removed).map(e -> e.entity).toList();
                case "spawn" -> spawn((Location) args[0], (Class<?>) args[1], args[2]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    @TempDir Path directory;
    private final List<String> routes = new ArrayList<>();
    private final List<String> added = new ArrayList<>();
    private final List<String> released = new ArrayList<>();
    private RoomBoards board() {
        return new RoomBoards(world, LobbyLayout::bundled, () -> Component.text("Rooms"),
                (id, player) -> { routes.add(id); opened.add(player); }, (at, ready) -> ready.run(),
                new BoardTickets((x, z) -> added.add(x + "," + z),
                        (x, z) -> released.add(x + "," + z)),
                Set.of("create-room", "room-list"), message -> { });
    }

    @SuppressWarnings("unchecked")
    private Entity spawn(Location at, Class<?> type, Object initializer) {
        FakeEntity fake = new FakeEntity(at, type);
        spawned.add(fake);
        ((Consumer<Entity>) initializer).accept(fake.entity);
        return fake.entity;
    }
    private final class FakeEntity {
        final Location at;
        final Class<?> type;
        final Map<String, Object> properties = new HashMap<>();
        final Map<Object, Object> data = new HashMap<>();
        boolean removed;
        final PersistentDataContainer pdc = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[] {PersistentDataContainer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> { data.put(args[0], args[2]); yield null; }
                    case "has" -> data.containsKey(args[0]);
                    case "get" -> data.get(args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final Entity entity;
        FakeEntity(Location at, Class<?> type) {
            this.at = at;
            this.type = type;
            entity = (Entity) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, args) -> {
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
    }
    private Player player(boolean allowed) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> allowed;
                    case "sendMessage" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test void resetAndLostEntityRecoveryRemoveDuplicatesAndBalanceSharedTickets() {
        RoomBoards board = board();
        FakeEntity decoration = new FakeEntity(new Location(world, 0, 0, 0), TextDisplay.class);
        spawned.add(decoration);
        board.place();
        assertEquals(5, world.getEntities().size());
        assertEquals(1, added.size()); // The obsidian layout's room boards share a chunk.
        FakeEntity lost = spawned.get(1);
        lost.removed = true;
        board.update();
        assertEquals(5, world.getEntities().size());
        assertTrue(spawned.get(2).removed);
        assertEquals(0, released.size());
        FakeEntity duplicate = new FakeEntity(lost.at, TextDisplay.class);
        duplicate.data.put(RoomBoards.KEY, "create-room");
        spawned.add(duplicate);
        board.update();
        assertTrue(duplicate.removed);
        board.place();
        assertEquals(5, world.getEntities().size());
        assertFalse(decoration.removed);
        board.close();
        assertEquals(1, world.getEntities().size());
        assertEquals(added, released);
    }

    @Test void cancelledUseAndAttackRouteBothBoardsAndPermissionsAreChecked() {
        RoomBoards board = board();
        board.place();
        Player player = player(true);
        for (FakeEntity fake : List.copyOf(spawned)) {
            if (fake.type != Interaction.class) { continue; }
            var use = new PlayerInteractEntityEvent(player, fake.entity, EquipmentSlot.HAND);
            use.setCancelled(true); // Lockdown can cancel first; the board still handles it.
            board.onUse(use);
            assertTrue(use.isCancelled());
            var attack = new PrePlayerAttackEntityEvent(player, fake.entity, true);
            board.onAttack(attack);
            assertTrue(attack.isCancelled());
            board.onUse(new PlayerInteractEntityEvent(player, fake.entity, EquipmentSlot.OFF_HAND));
            board.onUse(new PlayerInteractEntityEvent(player(false), fake.entity, EquipmentSlot.HAND));
        }
        assertEquals(4, opened.size());
        assertTrue(routes.containsAll(List.of("create-room", "room-list")));
        board.close();
    }

    @Test void disabledBeforeAsyncLoadCompletesDoesNotRecreateOrAcquireTickets() {
        List<Runnable> pending = new ArrayList<>();
        RoomBoards board = new RoomBoards(world, LobbyLayout::bundled, () -> Component.empty(),
                (id, player) -> { }, (at, ready) -> pending.add(ready),
                new BoardTickets((x, z) -> added.add("add"), (x, z) -> released.add("remove")),
                Set.of("create-room"), message -> { });
        board.place();
        board.close();
        pending.forEach(Runnable::run);
        assertTrue(spawned.isEmpty());
        assertTrue(added.isEmpty());
    }
    @Test void chunkLoadSweepsTaggedDuplicatesAndWorldReopenRebuilds() {
        RoomBoards board = board();
        board.place();
        FakeEntity duplicate = new FakeEntity(new Location(world, 0, 0, 0), Interaction.class);
        duplicate.data.put(RoomBoards.KEY, "room-list");
        spawned.add(duplicate);
        org.bukkit.Chunk chunk = (org.bukkit.Chunk) Proxy.newProxyInstance(
                org.bukkit.Chunk.class.getClassLoader(), new Class<?>[] {org.bukkit.Chunk.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getEntities" -> world.getEntities().toArray(Entity[]::new);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        board.onChunkLoad(new org.bukkit.event.world.ChunkLoadEvent(chunk, false));
        assertTrue(duplicate.removed);
        board.onWorldLoad(new org.bukkit.event.world.WorldLoadEvent(world));
        assertEquals(4, world.getEntities().size());
        board.close();
        assertEquals(added, released);
    }

    @Test void missingMarksAreSkippedAndWarnedOnlyOnce() {
        LobbyLayout base = LobbyLayout.bundled();
        LobbyLayout empty = new LobbyLayout(base.build(), base.bounds(), base.spawn(), base.roomArea(),
                base.shopArea(), Map.of(), base.pads());
        List<String> warnings = new ArrayList<>();
        RoomBoards board = new RoomBoards(world, () -> empty, () -> Component.empty(),
                (id, player) -> { }, (at, ready) -> ready.run(),
                new BoardTickets((x, z) -> added.add("add"), (x, z) -> released.add("remove")),
                Set.of("create-room", "room-list"), warnings::add);
        board.place();
        board.place();
        assertEquals(2, warnings.size());
        assertTrue(spawned.isEmpty());
        assertTrue(added.isEmpty());
    }


    @Test void restartAndResetRebuildFromActiveLayoutSpotsAndFacing() {
        AtomicReference<LobbyLayout> active = new AtomicReference<>(LobbyLayout.bundled());
        RoomBoards original = board();
        original.place();
        original.close();
        RoomBoards restarted = new RoomBoards(world, active::get, () -> Component.text("Rooms"),
                (id, player) -> { }, (at, ready) -> ready.run(),
                new BoardTickets((x, z) -> { }, (x, z) -> { }),
                Set.of("create-room", "room-list"), message -> { });
        restarted.place();
        assertBoardSpots(active.get());
        LobbyLayout base = active.get();
        ArenaPoint create = base.boards().get("room-list");
        ArenaPoint list = base.boards().get("create-room");
        active.set(new LobbyLayout(base.build(), base.bounds(), base.spawn(), base.roomArea(),
                base.shopArea(), Map.of("create-room", create, "room-list", list), base.pads()));
        restarted.place();
        assertBoardSpots(active.get());
        restarted.close();
    }

    private void assertBoardSpots(LobbyLayout layout) {
        List<FakeEntity> displays = spawned.stream()
                .filter(fake -> !fake.removed && fake.type == TextDisplay.class).toList();
        assertEquals(2, displays.size());
        for (FakeEntity display : displays) {
            ArenaPoint point = layout.boards().get(display.data.get(RoomBoards.KEY));
            assertEquals(point.x(), display.at.getX());
            assertEquals(point.y(), display.at.getY());
            assertEquals(point.z(), display.at.getZ());
            assertEquals(point.yaw(), display.at.getYaw());
        }
    }

    @Test void roomListTargetCoversTextOnSpawnAndResizesWithoutReplacingEntities() {
        List<RoomBoardText.Row> rooms = IntStream.range(0, 6)
                .mapToObj(i -> new RoomBoardText.Row("Host" + i, 1, 4, Privacy.PUBLIC, "dojo", "waiting"))
                .toList();
        Component longList = RoomBoardText.render(rooms, 5); // 13 explicit lines including overflow.
        AtomicReference<Component> content = new AtomicReference<>(longList);
        RoomBoards board = new RoomBoards(world, LobbyLayout::bundled, content::get,
                (id, player) -> { }, (at, ready) -> ready.run(),
                new BoardTickets((x, z) -> { }, (x, z) -> { }), Set.of("room-list"), message -> { });
        board.place();
        FakeEntity target = spawned.stream().filter(fake -> fake.type == Interaction.class)
                .findFirst().orElseThrow();
        double textY = LobbyLayout.bundled().boards().get("room-list").y();
        float initialHeight = (Float) target.properties.get("setInteractionHeight");
        assertTrue(target.at.getY() <= textY);
        assertTrue(target.at.getY() + initialHeight >= textY + 13 * 0.25);
        content.set(RoomBoardText.render(List.of(), 5));
        board.update();
        float shortHeight = (Float) target.properties.get("setInteractionHeight");
        assertTrue(shortHeight < initialHeight);
        assertTrue(target.at.getY() + shortHeight >= textY + 3 * 0.25);
        content.set(longList);
        board.update();
        assertEquals(initialHeight, target.properties.get("setInteractionHeight"));
        assertFalse(target.removed);
        assertEquals(2, spawned.size());
        board.close();
    }

    @Test void reloadedMessagesRefreshExistingCreateDisplayWithoutReplacingEntities() throws Exception {
        RoomBoards board = board();
        board.place();
        FakeEntity create = spawned.stream().filter(fake -> fake.type == TextDisplay.class
                && "create-room".equals(fake.data.get(RoomBoards.KEY))).findFirst().orElseThrow();
        Object previous = create.properties.get("text");
        Path messages = directory.resolve("messages.yml");
        java.nio.file.Files.writeString(messages, "lobby:\n  boards:\n    create: 'Open a new room'\n");
        try {
            com.ninja6.ninjaarena.message.Messages.use(com.ninja6.ninjaarena.message.Messages.read(
                    messages, warning -> { }));
            board.update();
            assertEquals(Component.text("Open a new room"), create.properties.get("text"));
            assertFalse(previous.equals(create.properties.get("text")));
            assertFalse(create.removed);
            assertEquals(4, spawned.size());
        } finally {
            com.ninja6.ninjaarena.message.Messages.use(com.ninja6.ninjaarena.message.Messages.bundled());
            board.close();
        }
    }

}
