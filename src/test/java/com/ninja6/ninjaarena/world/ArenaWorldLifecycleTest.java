package com.ninja6.ninjaarena.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class ArenaWorldLifecycleTest {

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static final class Generation {
        List<Player> players = List.of();
        Set<Chunk> forced = Set.of();
        Map<Chunk, List<Plugin>> tickets = Map.of();
        final List<String> calls = new ArrayList<>();
        double border;
        int view;
        int simulation;
        boolean dirtyDiskBlocks = true;
        boolean failConfiguration;
        UUID identity = new UUID(0, 7);
        final WorldBorder worldBorder = proxy(WorldBorder.class, (object, method, args) -> {
            if (method.getName().equals("setSize")) {
                border = (Double) args[0];
            }
            return null;
        });
        final World world = proxy(World.class, (object, method, args) -> switch (method.getName()) {
            case "getPlayers" -> players;
            case "getForceLoadedChunks" -> forced;
            case "getPluginChunkTickets" -> tickets;
            case "getWorldBorder" -> worldBorder;
            case "getUID" -> identity;
            case "setGameRule" -> {
                if (failConfiguration) {
                    throw new IllegalStateException("configuration failed");
                }
                yield true;
            }
            case "setAutoSave" -> { calls.add("autosave " + args[0]); yield null; }
            case "save" -> { calls.add("save"); yield null; }
            case "setViewDistance" -> { view = (Integer) args[0]; yield null; }
            case "setSimulationDistance" -> { simulation = (Integer) args[0]; yield null; }
            case "setTime", "setStorm", "setThundering" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    void idleReleaseDropsTheOldWorldAndDiagnosticsDoNotReopenIt() {
        Generation first = new Generation();
        Generation second = new Generation();
        int[] loads = {0};
        List<World> unloaded = new ArrayList<>();
        var manager = new ArenaWorld(first.world, () -> { loads[0]++; return second.world; }, world -> {
            unloaded.add(world);
            return true;
        });
        assertTrue(manager.releaseIfIdle(() -> true));
        assertTrue(manager.currentWorld().isEmpty());
        assertEquals(0, loads[0]);
        assertEquals(List.of(first.world), unloaded);
        assertFalse(first.calls.contains("save"));
        manager.close();
        assertEquals(0, loads[0], "shutdown must not reopen an idle world");
        assertThrows(IllegalStateException.class, manager::world);
    }

    @Test
    void everyGenerationIsRestoredBeforePublicAccessAndRetainsReloadedConfiguration() {
        Generation first = new Generation();
        Generation second = new Generation();
        var manager = new ArenaWorld(first.world, () -> second.world, world -> true);
        first.dirtyDiskBlocks = false; // Startup already restored the saved copy in memory.
        manager.onReopen(() -> {
            assertTrue(manager.currentWorld().isEmpty(), "not published before restoration");
            assertThrows(IllegalStateException.class, manager::world, "reentrant public reads fail closed");
            assertSame(second.world, manager.copyWorld());
            assertTrue(second.dirtyDiskBlocks, "a fresh disk generation can still contain an old match");
            second.dirtyDiskBlocks = false;
        });
        assertTrue(manager.releaseIfIdle(() -> true));
        manager.setDistances(12, 9);
        manager.setBorderSize(4096);
        assertTrue(manager.currentWorld().isEmpty(), "reload settings do not reopen the world");
        assertSame(second.world, manager.world());
        assertFalse(second.dirtyDiskBlocks);
        assertEquals(12, second.view);
        assertEquals(9, second.simulation);
        assertEquals(4096, second.border);
        assertEquals(List.of("autosave false"), second.calls);
    }

    @Test
    void eachActiveOperationMustFinishAndReleasesAreIdempotent() {
        Generation first = new Generation();
        var manager = new ArenaWorld(first.world, () -> first.world, world -> true);
        Runnable oldRequest = manager.acquire();
        Runnable newRequest = manager.acquire();
        oldRequest.run();
        oldRequest.run();
        assertFalse(manager.releaseIfIdle(() -> true), "an old completion cannot release the new request");
        newRequest.run();
        assertTrue(manager.releaseIfIdle(() -> true));
    }

    @Test
    void playersTicketsForcedChunksRegistryAndEditingEachPreventRetirement() {
        Generation generation = new Generation();
        int[] unloads = {0};
        var manager = new ArenaWorld(generation.world, () -> generation.world, world -> { unloads[0]++; return true; });
        assertFalse(manager.releaseIfIdle(() -> false), "held, editing, failed resets and unreadable registry");
        generation.players = List.of(proxy(Player.class, (object, method, args) -> null));
        assertFalse(manager.releaseIfIdle(() -> true));
        generation.players = List.of();
        Chunk chunk = proxy(Chunk.class, (object, method, args) -> switch (method.getName()) {
            case "hashCode" -> 0;
            case "equals" -> object == args[0];
            default -> null;
        });
        generation.forced = Set.of(chunk);
        assertFalse(manager.releaseIfIdle(() -> true));
        generation.forced = Set.of();
        generation.tickets = Map.of(chunk, List.of());
        assertFalse(manager.releaseIfIdle(() -> true));
        generation.tickets = Map.of();
        manager.setSavingEnabled(true);
        assertFalse(manager.releaseIfIdle(() -> true));
        assertEquals(0, unloads[0]);
        manager.setSavingEnabled(false);
        assertTrue(manager.releaseIfIdle(() -> true));
        assertEquals(List.of("autosave false", "autosave true", "save", "autosave false"), generation.calls);
    }

    @Test
    void cancelledUnloadKeepsTheCurrentGenerationAndGuardsReentrancy() {
        Generation generation = new Generation();
        ArenaWorld[] slot = new ArenaWorld[1];
        slot[0] = new ArenaWorld(generation.world, () -> generation.world, world -> {
            assertThrows(IllegalStateException.class, slot[0]::world);
            assertThrows(IllegalStateException.class, slot[0]::acquire);
            assertFalse(slot[0].releaseIfIdle(() -> true));
            return false;
        });
        assertFalse(slot[0].releaseIfIdle(() -> true));
        assertSame(generation.world, slot[0].world());
        assertSame(generation.world, slot[0].currentWorld().orElseThrow());
    }

    @Test
    void failedRestoreDiscardsTheUnpublishedGenerationWithoutSavingAndFailsClosed() {
        Generation first = new Generation();
        Generation second = new Generation();
        List<World> unloaded = new ArrayList<>();
        var manager = new ArenaWorld(first.world, () -> second.world, world -> { unloaded.add(world); return true; });
        manager.onReopen(() -> { throw new IllegalStateException("copy missing"); });
        assertTrue(manager.releaseIfIdle(() -> true));
        assertThrows(IllegalStateException.class, manager::world);
        assertTrue(manager.currentWorld().isEmpty());
        assertEquals(List.of(first.world, second.world), unloaded);
        assertFalse(second.calls.contains("save"));
        assertThrows(IllegalStateException.class, manager::world);
        assertThrows(IllegalStateException.class, manager::acquire);
        manager.close();
    }

    @Test
    void loaderFailureAndReentrantCloseCannotPublishAUsableGeneration() {
        Generation generation = new Generation();
        var failing = new ArenaWorld(generation.world, () -> { throw new IllegalStateException("load failed"); },
                world -> true);
        assertTrue(failing.releaseIfIdle(() -> true));
        assertThrows(IllegalStateException.class, failing::world);
        assertTrue(failing.currentWorld().isEmpty());
        assertThrows(IllegalStateException.class, failing::acquire);

        ArenaWorld[] slot = new ArenaWorld[1];
        slot[0] = new ArenaWorld(generation.world, () -> {
            slot[0].close();
            return generation.world;
        }, world -> true);
        assertTrue(slot[0].releaseIfIdle(() -> true));
        assertThrows(IllegalStateException.class, slot[0]::world);
        assertTrue(slot[0].currentWorld().isEmpty());
        assertThrows(IllegalStateException.class, slot[0]::acquire);
    }

    @Test
    void reentrantCloseDuringUnloadCannotResurrectTheManager() {
        Generation generation = new Generation();
        ArenaWorld[] slot = new ArenaWorld[1];
        slot[0] = new ArenaWorld(generation.world, () -> generation.world, world -> {
            slot[0].close();
            return true;
        });
        assertTrue(slot[0].releaseIfIdle(() -> true));
        assertTrue(slot[0].currentWorld().isEmpty());
        assertThrows(IllegalStateException.class, slot[0]::world);
    }

    @Test
    void configurationFailureOrChangedUidDiscardTheUnpublishedWorld() {
        for (boolean changedUid : List.of(false, true)) {
            Generation first = new Generation();
            Generation second = new Generation();
            second.failConfiguration = !changedUid;
            if (changedUid) {
                second.identity = new UUID(0, 8);
            }
            int[] unloads = {0};
            var manager = new ArenaWorld(first.world, () -> second.world, world -> { unloads[0]++; return true; },
                    first.identity);
            manager.onReopen(() -> { throw new AssertionError("restoration must not start"); });
            assertTrue(manager.releaseIfIdle(() -> true));
            assertThrows(IllegalStateException.class, manager::world);
            assertTrue(manager.currentWorld().isEmpty());
            assertEquals(2, unloads[0]);
            assertFalse(second.calls.contains("save"));
        }
    }
}
