package com.ninja6.ninjaarena.reward;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.LobbyWorld;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

/** A claimed trophy waits while the player is in either plugin world or busy in the Arena. */
class TrophyDeliveryTest {

    private static final UUID ALICE = new UUID(0, 1);

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Player player(World world, boolean dead, int freeSlot) {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[] {PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "firstEmpty" -> freeSlot;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ALICE;
                    case "getWorld" -> world;
                    case "isDead" -> dead;
                    case "getInventory" -> inventory;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void neverDeliveredInsideAPluginWorld() {
        for (String name : Set.of(LobbyWorld.NAME, ArenaWorld.NAME)) {
            assertFalse(Rewards.deliverable(player(world(name), false, 0), id -> false, id -> false), name);
        }
        assertTrue(Rewards.deliverable(player(world("world"), false, 0), id -> false, id -> false));
    }

    @Test
    void neverDeliveredToALobbyMemberOrAnyoneBusyEvenOutsideThePluginWorlds() {
        assertFalse(Rewards.deliverable(player(world("world"), false, 0), ALICE::equals, id -> false));
    }

    @Test
    void waitsForARestoreALivingPlayerAndAFreeSlot() {
        assertFalse(Rewards.deliverable(null, id -> false, id -> false));
        assertFalse(Rewards.deliverable(player(world("world"), false, 0), id -> false, ALICE::equals));
        assertFalse(Rewards.deliverable(player(world("world"), true, 0), id -> false, id -> false));
        assertFalse(Rewards.deliverable(player(world("world"), false, -1), id -> false, id -> false));
    }
}
