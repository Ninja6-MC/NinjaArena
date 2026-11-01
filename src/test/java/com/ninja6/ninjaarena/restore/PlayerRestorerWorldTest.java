package com.ninja6.ninjaarena.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.support.TestServer;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class PlayerRestorerWorldTest {

    @Test
    void anArenaOriginSnapshotReopensItsCanonicalWorldInsteadOfUsingTheMissingWorldFallback() {
        UUID identity = new UUID(0, 77);
        SnapshotLocation saved = new SnapshotLocation(identity, ArenaWorld.NAME, 40.5, 64, -20.5, 30, 10);
        World reopened = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (object, method, args) -> switch (method.getName()) {
                    case "getUID" -> identity;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        int[] loads = {0};
        PlayerRestorer restorer = new PlayerRestorer(Logger.getAnonymousLogger(), () -> {
            loads[0]++;
            return reopened;
        });
        assertSame(reopened, restorer.savedWorld(saved));
        assertEquals(identity, restorer.savedWorld(saved).getUID());
        assertEquals(2, loads[0]);
    }

    @Test
    void failedArenaReopenPropagatesSoRestorationKeepsTheSnapshotRatherThanFallingBack() {
        SnapshotLocation saved = new SnapshotLocation(new UUID(0, 77), ArenaWorld.NAME, 40.5, 64, -20.5, 30, 10);
        PlayerRestorer restorer = new PlayerRestorer(Logger.getAnonymousLogger(), () -> {
            throw new IllegalStateException("authoritative copy unavailable");
        });
        assertThrows(IllegalStateException.class, () -> restorer.savedWorld(saved));
    }

    @Test
    void otherMissingWorldsKeepTheExistingFallbackWithoutLoadingTheArenaWorld() {
        TestServer.install();
        SnapshotLocation saved = new SnapshotLocation(new UUID(0, 88), "missing", 0, 64, 0, 0, 0);
        PlayerRestorer restorer = new PlayerRestorer(Logger.getAnonymousLogger(), () -> {
            throw new AssertionError("unrelated world restore must not load the arena world");
        });
        assertNull(restorer.savedWorld(saved));
    }

    @Test
    void outOfExtentArenaSnapshotDoesNotLoadOrSearchItsOldChunk() {
        var saved = new SnapshotLocation(new UUID(0, 77), ArenaWorld.NAME, 50000, 64, 50000, 0, 0);
        var restorer = new PlayerRestorer(Logger.getAnonymousLogger(), () -> {
            throw new AssertionError("outside snapshot must use the safe fallback before opening the world");
        });
        assertNull(restorer.savedWorld(saved));
    }

    @Test
    void configuredBorderShrinkUsesFallbackBeforeAnyArenaLandingSearch() {
        var saved = new SnapshotLocation(new UUID(0, 77), ArenaWorld.NAME, 500, 64, 500, 0, 0);
        var restorer = new PlayerRestorer(Logger.getAnonymousLogger(), () -> {
            throw new AssertionError("a refused configured destination must not request its terrain");
        }, (x, z) -> com.ninja6.ninjaarena.world.ArenaExtent.contains(x, z, 128));
        assertNull(restorer.savedWorld(saved));
    }

    @Test
    void nearbySearchTreatsOutsideColumnsAsUnsafeBeforeReadingThem() {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (object, method, args) -> {
                    if (method.getName().equals("getName")) return ArenaWorld.NAME;
                    throw new AssertionError("Outside search read terrain: " + method.getName());
                });
        var terrain = new WorldTerrain(world, (x, z) -> com.ninja6.ninjaarena.world.ArenaExtent.contains(x, z, 128));
        assertEquals(SafeLanding.Cell.LAVA, terrain.at(64, 319, 0));
        assertEquals(1, terrain.height(64, 319, 0));
    }
}
