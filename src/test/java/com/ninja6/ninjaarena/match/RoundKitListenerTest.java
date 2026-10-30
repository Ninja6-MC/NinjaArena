package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.kit.RoundKitListener;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.PlayerRestorer;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.support.TestItemStack;
import com.ninja6.ninjaarena.support.TestServer;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class RoundKitListenerTest extends MatchFixture {
    private RoundKitListener kits;
    private MatchProtectionListener protection;
    private final KitItems items = new KitItems();
    private static final KitDefinition KIT = new KitDefinition(List.of(
            new KitDefinition.Entry(0, null, "test:tool", 2),
            new KitDefinition.Entry(39, null, "test:helmet", 1),
            new KitDefinition.Entry(40, null, "test:arrows", 8)));

    private void configure(KitDefinition kit, Function<Player, RestoreResult> restore) {
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, restore, event -> {
            events.add(event);
            switch (event) {
                case PlayerEliminatedEvent out -> deaths.onEliminated(out);
                case PlayerLeaveMatchEvent left -> deaths.onLeave(left);
                case RoundStartEvent started -> {
                    deaths.onRoundStart(started);
                    protection.onRoundStart(started);
                    kits.onRoundStart(started);
                }
                default -> { }
            }
        }, player -> readied.add(player.getUniqueId()));
        deaths = new MatchDeathListener(plugin, matches, arenas);
        listener = new MatchListener(matches, deaths);
        protection = new MatchProtectionListener(matches, plugin.getServer()::getPlayer, false, deaths::isSpectating);
        gameModeChanges = protection::onGameMode;
        kits = new RoundKitListener(matches, plugin.getServer()::getPlayer, kit, items, plugin.getLogger());
    }

    private void standardKit() {
        items.register("test:tool", player -> new TestItemStack(Material.PAPER, "tool-data", 1));
        items.register("test:helmet", player -> new TestItemStack(Material.LEATHER_HELMET, "helmet", 1));
        items.register("test:arrows", player -> new TestItemStack(Material.ARROW, "arrows", 1));
        configure(KIT, player -> RestoreResult.RESTORED);
    }

    private void die(UUID id) {
        players.get(id).dead = true;
        matches.died(player(id), null);
    }

    private void respawn(UUID id) {
        var event = new PlayerRespawnEvent(player(id), player(id).getLocation(), false, false, false,
                PlayerRespawnEvent.RespawnReason.DEATH);
        listener.onRespawn(event);
        players.get(id).dead = false;
        players.get(id).location = event.getRespawnLocation();
        var post = new PlayerPostRespawnEvent(player(id), event.getRespawnLocation(), false, false, false,
                PlayerRespawnEvent.RespawnReason.DEATH);
        deaths.onPostRespawn(post);
        kits.onPostRespawn(post);
    }

    @Test
    void everyRoundRebuildsStorageArmourOffhandCursorAndCraftingFromScratch() {
        standardKit();
        startTwoOnTwo();
        Fake alice = players.get(ALICE);
        assertEquals("tool-data", ((TestItemStack) alice.storage[0]).marker());
        assertEquals(2, alice.storage[0].getAmount());
        alice.storage[0].setAmount(1);
        alice.storage[12] = new TestItemStack(Material.STONE, "leftover", 20);
        alice.armour[0] = new TestItemStack(Material.LEATHER_BOOTS, "leftover", 1);
        alice.offhand.setAmount(1);
        alice.cursor = new TestItemStack(Material.STONE, "cursor", 4);
        alice.crafting[1] = new TestItemStack(Material.STONE, "crafting", 4);
        die(ALICE);
        respawn(ALICE);
        assertEquals(GameMode.SPECTATOR, player(ALICE).getGameMode());
        assertEquals(1, alice.storage[0].getAmount());
        assertEquals(20, alice.storage[12].getAmount());
        die(BOB);
        die(DAVE);
        runNextTick();
        ticks(1);
        assertEquals(GameMode.ADVENTURE, player(ALICE).getGameMode());
        assertAt(SPAWN_1, player(ALICE).getLocation());
        assertEquals(2, alice.storage[0].getAmount());
        assertNull(alice.storage[12]);
        assertNull(alice.armour[0]);
        assertEquals("helmet", ((TestItemStack) alice.armour[3]).marker());
        assertEquals(8, alice.offhand.getAmount());
        assertNull(alice.cursor);
        assertTrue(Arrays.stream(alice.crafting).allMatch(item -> item == null));
    }

    @Test
    void aDelayedRespawnIntoTheNextRoundGetsAFreshKitAfterPlacement() {
        standardKit();
        startTwoOnTwo();
        players.get(ALICE).storage[0].setAmount(1);
        die(ALICE);
        die(BOB);
        die(DAVE);
        runNextTick();
        int teleports = players.get(ALICE).teleports.size();
        ticks(1);
        assertTrue(players.get(ALICE).dead);
        assertEquals(teleports, players.get(ALICE).teleports.size());
        assertEquals(1, players.get(ALICE).storage[0].getAmount());
        respawn(ALICE);
        assertAt(SPAWN_1, player(ALICE).getLocation());
        assertEquals(GameMode.ADVENTURE, player(ALICE).getGameMode());
        assertEquals(2, players.get(ALICE).storage[0].getAmount());
    }

    @Test
    void unknownIdsAndThrowingProvidersGiveAnEmptyKitWithoutPartiallyApplyingIt() {
        items.register("test:valid", player -> new TestItemStack(Material.PAPER, "valid", 1));
        for (String id : List.of("test:unknown", "test:broken")) {
            if (id.equals("test:broken")) {
                items.register(id, player -> { throw new IllegalStateException("provider failed"); });
            }
            configure(new KitDefinition(List.of(new KitDefinition.Entry(0, null, "test:valid", 1),
                            new KitDefinition.Entry(1, null, id, 1))),
                    player -> RestoreResult.RESTORED);
            players.get(ALICE).storage = new ItemStack[] {new TestItemStack(Material.STONE, "old", 1)};
            startTwoOnTwo();
            assertTrue(Arrays.stream(players.get(ALICE).storage).allMatch(item -> item == null));
            assertNull(players.get(ALICE).offhand);
            matches.close();
        }
    }

    @Test
    void missingAndMalformedKitFilesStillAllowMatchesToStartWithSafeEmptyKits() throws Exception {
        Files.writeString(dir.resolve("malformed-kits.yml"), "items: [unterminated");
        for (String file : List.of("missing-kits.yml", "malformed-kits.yml")) {
            configure(KitDefinition.load(dir.resolve(file), plugin.getLogger()),
                    player -> RestoreResult.RESTORED);
            players.get(ALICE).storage = new ItemStack[] {new TestItemStack(Material.STONE, "old", 1)};
            startTwoOnTwo();
            assertTrue(matches.isInMatch(ALICE));
            assertEquals(MatchPhase.PREP, matches.matchOf(ALICE).orElseThrow().phase());
            assertTrue(Arrays.stream(players.get(ALICE).storage).allMatch(item -> item == null));
            matches.close();
        }
    }

    @Test
    void personalInventoryIsRestoredAfterBothLeavingAndMatchClose() throws Exception {
        TestServer.install();
        Logger logger = Logger.getAnonymousLogger();
        var snapshots = new SnapshotService(new SnapshotStore(dir.resolve("snapshots")), logger);
        var restores = new RestoreService(snapshots, new PlayerRestorer(logger), logger);
        standardKit();
        var ownStorage = new ItemStack[] {new TestItemStack(Material.DIAMOND, "personal-storage", 11)};
        var ownArmour = new ItemStack[] {
                new TestItemStack(Material.LEATHER_BOOTS, "personal-boots", 1),
                new TestItemStack(Material.LEATHER_LEGGINGS, "personal-legs", 1),
                new TestItemStack(Material.LEATHER_CHESTPLATE, "personal-chest", 1),
                new TestItemStack(Material.LEATHER_HELMET, "personal-helmet", 1)};
        var ownOffhand = new ItemStack[] {new TestItemStack(Material.SHIELD, "personal-offhand", 1)};
        byte[] storageBytes = ItemStack.serializeItemsAsBytes(ownStorage);
        byte[] armourBytes = ItemStack.serializeItemsAsBytes(ownArmour);
        byte[] offhandBytes = ItemStack.serializeItemsAsBytes(ownOffhand);
        for (boolean close : List.of(false, true)) {
            snapshots.persist(new PlayerSnapshot(ALICE, "Alice", Instant.now(),
                    new SnapshotLocation(new UUID(0, 99), "world", 0.5, 64, 0.5, 0, 0),
                    storageBytes, armourBytes, offhandBytes, 0, 0, 0, 20, 20, 5, 0,
                    List.of(), GameMode.CREATIVE, 0, 0));
            configure(KIT, restores::restore);
            startTwoOnTwo();
            assertEquals("tool-data", ((TestItemStack) players.get(ALICE).storage[0]).marker());
            if (close) matches.close();
            else matches.leave(player(ALICE), MatchService.Exit.COMMAND);
            assertFalse(matches.isInMatch(ALICE));
            assertFalse(snapshots.hasPending(ALICE));
            assertArrayEquals(storageBytes, ItemStack.serializeItemsAsBytes(players.get(ALICE).storage));
            assertArrayEquals(armourBytes, ItemStack.serializeItemsAsBytes(players.get(ALICE).armour));
            assertArrayEquals(offhandBytes, ItemStack.serializeItemsAsBytes(new ItemStack[] {players.get(ALICE).offhand}));
            assertEquals(GameMode.CREATIVE, player(ALICE).getGameMode());
            matches.close();
        }
    }

    private void addKitLeftovers() {
        Fake alice = players.get(ALICE);
        alice.cursor = new TestItemStack(Material.STONE, "cursor", 1);
        alice.crafting[1] = new TestItemStack(Material.STONE, "crafting", 1);
        alice.armour[0] = new TestItemStack(Material.LEATHER_BOOTS, "boots", 1);
        assertTrue(Arrays.stream(alice.storage).anyMatch(item -> item != null));
        assertTrue(alice.offhand != null);
    }

    private void assertEmptyInventory(UUID id) {
        Fake fake = players.get(id);
        assertTrue(Arrays.stream(fake.storage).allMatch(item -> item == null));
        assertTrue(Arrays.stream(fake.armour).allMatch(item -> item == null));
        assertNull(fake.offhand);
        assertNull(fake.cursor);
        assertTrue(Arrays.stream(fake.crafting).allMatch(item -> item == null));
    }

    @Test
    void failedCommandAndTeleportRestoresCannotLeaveKitItemsOutsideTheMatch() {
        standardKit();
        for (MatchService.Exit exit : List.of(MatchService.Exit.COMMAND, MatchService.Exit.TELEPORTED)) {
            configure(KIT, player -> {
                assertEmptyInventory(player.getUniqueId());
                return RestoreResult.FAILED;
            });
            startTwoOnTwo();
            addKitLeftovers();
            assertTrue(matches.leave(player(ALICE), exit));
            assertFalse(matches.isInMatch(ALICE));
            assertEmptyInventory(ALICE);
            runNextTick();
            assertEmptyInventory(ALICE);
            matches.close();
        }
    }

    @Test
    void quittingMidMatchClearsKitItemsBeforePlayerDataIsSavedAndBeforeAnyRejoinRestore() {
        standardKit();
        configure(KIT, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.FAILED;
        });
        startTwoOnTwo();
        addKitLeftovers();
        listener.onQuit(new PlayerQuitEvent(player(ALICE), net.kyori.adventure.text.Component.empty()));
        assertFalse(matches.isInMatch(ALICE));
        assertEmptyInventory(ALICE);
        assertTrue(restored.isEmpty());
    }

    @Test
    void matchCloseAndDisableClearKitsBeforeFailedRestoresWithoutLeaveEvents() {
        standardKit();
        configure(KIT, player -> {
            assertEmptyInventory(player.getUniqueId());
            return RestoreResult.FAILED;
        });
        startTwoOnTwo();
        addKitLeftovers();
        matches.close();
        deaths.close();
        assertTrue(events(PlayerLeaveMatchEvent.class).isEmpty());
        for (UUID id : List.of(ALICE, BOB, CAROL, DAVE)) {
            assertFalse(matches.isInMatch(id));
            assertEmptyInventory(id);
        }
    }

    @Test
    void kitReloadWaitsForNextRoundAndDelayedRespawnUsesItsRoundsDefinition() {
        standardKit();
        startTwoOnTwo();
        items.register("test:new", player -> new TestItemStack(Material.PAPER, "new-kit", 1));
        var next = new KitDefinition(List.of(new KitDefinition.Entry(0, null, "test:new", 1)));
        players.get(ALICE).dead = true;
        kits.setDefinition(next);
        respawn(ALICE);
        assertEquals("tool-data", ((TestItemStack) players.get(ALICE).storage[0]).marker());
        die(ALICE);
        die(BOB);
        die(DAVE);
        runNextTick();
        ticks(1);
        assertTrue(players.get(ALICE).dead);
        respawn(ALICE);
        assertEquals("new-kit", ((TestItemStack) players.get(ALICE).storage[0]).marker());
        assertEquals("new-kit", ((TestItemStack) players.get(CAROL).storage[0]).marker());
    }

}
