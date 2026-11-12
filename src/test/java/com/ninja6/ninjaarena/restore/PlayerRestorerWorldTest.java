package com.ninja6.ninjaarena.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.support.TestServer;
import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.LobbyWorld;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlayerRestorerWorldTest {

    /** A plugin world that fails the test if anything but its name is read. */
    private static World untouchable(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (object, method, args) -> {
                    if (method.getName().equals("getName")) {
                        return name;
                    }
                    throw new AssertionError("Read " + name + ": " + method.getName());
                });
    }

    private static Player respawningIn(Location respawn) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (object, method, args) -> switch (method.getName()) {
                    case "getRespawnLocation" -> respawn;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {ArenaWorld.NAME, LobbyWorld.NAME})
    void aSavedSpotInAPluginWorldIsNeverALanding(String world) {
        SnapshotLocation saved = new SnapshotLocation(new UUID(0, 77), world, 40.5, 64, -20.5, 30, 10);
        assertNull(PlayerRestorer.savedWorld(saved));
    }

    @ParameterizedTest
    @ValueSource(strings = {ArenaWorld.NAME, LobbyWorld.NAME})
    void aPluginWorldSavedSpotAndRespawnPointFallBackToTheWorldSpawn(String world) {
        TestServer.install();
        SnapshotLocation saved = new SnapshotLocation(new UUID(0, 77), world, 40.5, 64, -20.5, 30, 10);
        Player player = respawningIn(new Location(untouchable(world), 1.5, 64, 1.5));

        PlayerRestorer.Landing landing = new PlayerRestorer(Logger.getAnonymousLogger()).landing(player, saved);

        assertEquals(PlayerRestorer.Landing.Source.WORLD_SPAWN, landing.source());
        assertEquals("world", landing.location().getWorld().getName());
    }

    @Test
    void otherMissingWorldsKeepTheExistingFallback() {
        TestServer.install();
        SnapshotLocation saved = new SnapshotLocation(new UUID(0, 88), "missing", 0, 64, 0, 0, 0);
        assertNull(PlayerRestorer.savedWorld(saved));
    }

    @ParameterizedTest
    @ValueSource(strings = {ArenaWorld.NAME, LobbyWorld.NAME})
    void terrainInAPluginWorldIsNeverSafeAndIsNeverRead(String name) {
        var terrain = new WorldTerrain(untouchable(name));
        assertEquals(SafeLanding.Cell.LAVA, terrain.at(0, 64, 0));
        assertEquals(1, terrain.height(0, 64, 0));
    }
}
