package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.kit.InventoryLockdownListener;
import com.ninja6.ninjaarena.kit.KitInventory;
import com.ninja6.ninjaarena.restore.PlayerRestorer;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.support.TestItemStack;
import com.ninja6.ninjaarena.support.TestServer;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import com.ninja6.ninjaarena.waiting.WaitingRoomService.Exit;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WaitingRoomInventoryTest extends MatchFixture {

    private SnapshotService snapshots;
    private RestoreService restores;
    private WaitingRoomService rooms;
    private InventoryLockdownListener lockdown;

    @BeforeEach
    void setUpRooms() throws Exception {
        TestServer.install();
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        snapshots = new SnapshotService(new SnapshotStore(dir.resolve("snapshots")), logger);
        restores = new RestoreService(snapshots, new PlayerRestorer(logger), logger);
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, restores::restore, events::add,
                player -> readied.add(player.getUniqueId()));
        rooms = rooms(KitInventory::emptyForWaiting);
    }

    private WaitingRoomService rooms(Consumer<Player> empty) {
        WaitingRoomService service = new WaitingRoomService(plugin, arenas, new WaitingRoomSettings(2, 2, 16),
                restores::restore, empty, matches);
        lockdown = new InventoryLockdownListener(id -> matches.isInMatch(id) || service.isWaiting(id));
        return service;
    }

    /** Overworld belongings: three golden apples, elytra and armour, a shield, speed and fire. */
    private void equip(UUID id) {
        Fake fake = players.get(id);
        fake.location = new Location(overworld, 0.5, 64, 0.5);
        fake.gameMode = GameMode.SURVIVAL;
        fake.storage = new ItemStack[] {new TestItemStack(Material.GOLDEN_APPLE, "apples", 3),
                new TestItemStack(Material.FIREWORK_ROCKET, "rockets", 16)};
        fake.armour = new ItemStack[] {new TestItemStack(Material.LEATHER_BOOTS, "boots", 1),
                new TestItemStack(Material.LEATHER_LEGGINGS, "legs", 1),
                new TestItemStack(Material.ELYTRA, "elytra", 1),
                new TestItemStack(Material.LEATHER_HELMET, "helmet", 1)};
        fake.offhand = new TestItemStack(Material.TOTEM_OF_UNDYING, "totem", 1);
        fake.effects.add(new PotionEffect(PotionEffectType.SPEED, 600, 1));
        fake.fireTicks = 60;
    }

    /** The portal's order: snapshot on disk, teleport into the room, then join. */
    private void arrive(UUID id) {
        assertEquals(CaptureResult.SAVED, snapshots.captureAndPersist(player(id)));
        assertTrue(player(id).teleport(new Location(arenaWorld, ROOM.x(), ROOM.y(), ROOM.z())));
        rooms.join(player(id), "dojo");
    }

    private void assertEmpty(UUID id) {
        Fake fake = players.get(id);
        assertTrue(Arrays.stream(fake.storage).allMatch(Objects::isNull));
        assertTrue(Arrays.stream(fake.armour).allMatch(Objects::isNull));
        assertNull(fake.offhand);
        assertNull(fake.cursor);
        assertTrue(Arrays.stream(fake.crafting).allMatch(Objects::isNull));
    }

    private static byte[] bytes(ItemStack... items) {
        return ItemStack.serializeItemsAsBytes(items);
    }

    private InventoryView view(Player player, InventoryType type) {
        Inventory top = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class}, (p, m, a) -> switch (m.getName()) {
                    case "getType" -> type;
                    case "equals" -> p == a[0];
                    case "getHolder" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                new Class<?>[] {InventoryView.class}, (p, m, a) -> switch (m.getName()) {
                    case "getPlayer" -> player;
                    case "getTopInventory" -> top;
                    case "getBottomInventory" -> player.getInventory();
                    case "getType" -> type;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static Item item() {
        return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[] {Item.class},
                (p, m, a) -> { throw new UnsupportedOperationException(m.getName()); });
    }

    private PlayerInteractEvent enderChestClick(UUID id) {
        BlockState state = (BlockState) Proxy.newProxyInstance(BlockState.class.getClassLoader(),
                new Class<?>[] {BlockState.class}, (p, m, a) -> { throw new UnsupportedOperationException(); });
        Block block = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getType" -> Material.ENDER_CHEST;
                    case "getState" -> state;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return new PlayerInteractEvent(player(id), Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP);
    }

    @Test
    void arrivingEmptiesTheInventoryEffectsAndFireAndSetsAdventureMode() {
        equip(ALICE);
        players.get(ALICE).cursor = new TestItemStack(Material.STONE, "cursor", 1);
        players.get(ALICE).crafting[1] = new TestItemStack(Material.STONE, "crafting", 1);
        arrive(ALICE);
        assertTrue(rooms.isWaiting(ALICE));
        assertEmpty(ALICE);
        assertTrue(players.get(ALICE).effects.isEmpty());
        assertEquals(0, players.get(ALICE).fireTicks);
        assertEquals(GameMode.ADVENTURE, player(ALICE).getGameMode());
        assertTrue(snapshots.hasPending(ALICE));
    }

    @Test
    void dropsPickupsAndInventoryViewsAreCancelledWhileWaiting() {
        equip(ALICE);
        arrive(ALICE);
        var drop = new PlayerDropItemEvent(player(ALICE), item());
        lockdown.onDrop(drop);
        assertTrue(drop.isCancelled());
        var pickup = new EntityPickupItemEvent(player(ALICE), item(), 0);
        lockdown.onPickup(pickup);
        assertTrue(pickup.isCancelled());
        var attempt = new PlayerAttemptPickupItemEvent(player(ALICE), item(), 0);
        lockdown.onAttemptPickup(attempt);
        assertTrue(attempt.isCancelled());
        // An ender chest opened from a block or through a command, and any other container.
        for (InventoryType type : List.of(InventoryType.ENDER_CHEST, InventoryType.CHEST, InventoryType.SHULKER_BOX)) {
            var open = new InventoryOpenEvent(view(player(ALICE), type));
            lockdown.onOpen(open);
            assertTrue(open.isCancelled(), type.name());
        }
        var block = enderChestClick(ALICE);
        lockdown.onInteract(block);
        assertEquals(Event.Result.DENY, block.useInteractedBlock());
        var own = new InventoryOpenEvent(players.get(ALICE).view);
        lockdown.onOpen(own);
        assertFalse(own.isCancelled());

        var outside = new PlayerDropItemEvent(player(CAROL), item());
        lockdown.onDrop(outside);
        assertFalse(outside.isCancelled());
        var chest = new InventoryOpenEvent(view(player(CAROL), InventoryType.ENDER_CHEST));
        lockdown.onOpen(chest);
        assertFalse(chest.isCancelled());
    }

    @Test
    void leavingRestoresTheOriginalInventoryGameModeAndEffects() {
        equip(ALICE);
        Fake alice = players.get(ALICE);
        byte[] storage = bytes(alice.storage);
        byte[] armour = bytes(alice.armour);
        byte[] offhand = bytes(alice.offhand);
        arrive(ALICE);
        assertTrue(rooms.leave(player(ALICE), Exit.COMMAND));
        assertFalse(rooms.isWaiting(ALICE));
        assertFalse(snapshots.hasPending(ALICE));
        assertArrayEquals(storage, bytes(alice.storage));
        assertArrayEquals(armour, bytes(alice.armour));
        assertArrayEquals(offhand, bytes(alice.offhand));
        assertEquals(GameMode.SURVIVAL, player(ALICE).getGameMode());
        assertEquals(List.of(PotionEffectType.SPEED), alice.effects.stream().map(PotionEffect::getType).toList());
        assertEquals(60, alice.fireTicks);
    }

    @Test
    void aConsumableCannotBeUsedWhileWaitingAndComesBackOnlyOnce() {
        equip(ALICE);
        arrive(ALICE);
        Fake alice = players.get(ALICE);
        assertTrue(Arrays.stream(alice.storage).noneMatch(item -> item != null
                && item.getType() == Material.GOLDEN_APPLE));
        // Nor can one be picked up from the floor to eat.
        var pickup = new PlayerAttemptPickupItemEvent(player(ALICE), item(), 0);
        lockdown.onAttemptPickup(pickup);
        assertTrue(pickup.isCancelled());
        rooms.leave(player(ALICE), Exit.COMMAND);
        int apples = Arrays.stream(alice.storage).filter(item -> item != null
                && item.getType() == Material.GOLDEN_APPLE).mapToInt(ItemStack::getAmount).sum();
        assertEquals(3, apples);
    }

    @Test
    void aPlayerWhoCannotBeEmptiedIsSentHomeWithTheirBelongings() {
        rooms = rooms(player -> {
            throw new IllegalStateException("inventory locked by another plugin");
        });
        equip(ALICE);
        byte[] storage = bytes(players.get(ALICE).storage);
        arrive(ALICE);
        assertFalse(rooms.isWaiting(ALICE));
        assertFalse(snapshots.hasPending(ALICE));
        assertArrayEquals(storage, bytes(players.get(ALICE).storage));
        assertTrue(players.get(ALICE).messages.contains("You could not join the waiting room, so you were sent back."));
    }

    @Test
    void waitingPlayersStayLockedWhenTheMatchStarts() {
        equip(ALICE);
        equip(BOB);
        arrive(ALICE);
        arrive(BOB);
        rooms.tick();
        rooms.tick();
        assertFalse(rooms.isWaiting(ALICE));
        assertTrue(matches.isInMatch(ALICE));
        assertEmpty(ALICE);
        var drop = new PlayerDropItemEvent(player(ALICE), item());
        lockdown.onDrop(drop);
        assertTrue(drop.isCancelled());
        matches.leave(player(ALICE), MatchService.Exit.COMMAND);
        assertFalse(snapshots.hasPending(ALICE));
        assertEquals(GameMode.SURVIVAL, player(ALICE).getGameMode());
        assertEquals(3, players.get(ALICE).storage[0].getAmount());
    }
}
