package com.ninja6.ninjaarena.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.lang.reflect.Proxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;

class ArenaExtentTest {
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    @Test
    void extentIsHalfOpenFiniteAndCannotBeEnlargedPastTheHardLimit() {
        assertTrue(ArenaExtent.contains(-1024, -1024));
        assertTrue(ArenaExtent.contains(1023.999, 1023.999));
        assertFalse(ArenaExtent.contains(1024, 0));
        assertFalse(ArenaExtent.contains(-1024.001, 0));
        assertFalse(ArenaExtent.contains(0, Double.NaN));
        assertFalse(ArenaExtent.contains(Double.POSITIVE_INFINITY, 0));
        assertFalse(ArenaExtent.contains(1024, 0, Double.MAX_VALUE));
        assertEquals(2048, new ArenaWorldSettings(Double.NaN, 6, 4).borderSize());
    }

    @Test
    void movementAndTeleportEnforceTheConfiguredEnvelopeInEveryGameModeAndAllowLeaving() {
        World arena = proxy(World.class, (o, m, a) -> ArenaWorld.NAME);
        World other = proxy(World.class, (o, m, a) -> "world");
        Player player = proxy(Player.class, (o, m, a) -> { throw new AssertionError(m.getName()); });
        var listener = new ArenaExtentListener(() -> 128, new Location(other, 0, 64, 0));
        Location from = new Location(arena, 63, 64, 0);
        var movement = new PlayerMoveEvent(player, from, new Location(arena, 64, 64, 0));
        listener.onMove(movement);
        assertTrue(movement.isCancelled());
        var entry = new PlayerTeleportEvent(player, new Location(other, 0, 64, 0), new Location(arena, -65, 64, 0));
        listener.onTeleport(entry);
        assertTrue(entry.isCancelled());
        var exit = new PlayerTeleportEvent(player, from, new Location(other, 50000, 64, 0));
        listener.onTeleport(exit);
        assertFalse(exit.isCancelled());
        var inside = new PlayerMoveEvent(player, from, new Location(arena, -64, 64, 0));
        listener.onMove(inside);
        assertFalse(inside.isCancelled());
    }

    @Test
    void asynchronousLoginUsesACachedFallbackWithoutCreatingOrReadingAPlayer() {
        World arena = proxy(World.class, (o, m, a) -> ArenaWorld.NAME);
        World other = proxy(World.class, (o, m, a) -> "world");
        var connection = proxy(PlayerConfigurationConnection.class,
                (o, m, a) -> { throw new AssertionError(m.getName()); });
        var listener = new ArenaExtentListener(() -> 128, new Location(other, 8.5, 64, 8.5));
        var event = new AsyncPlayerSpawnLocationEvent(connection, new Location(arena, 10000, 64, 0), false);
        listener.onSpawn(event);
        assertTrue(event.isAsynchronous());
        assertEquals(8.5, event.getSpawnLocation().getX());
        assertEquals(8.5, event.getSpawnLocation().getZ());
    }
}
