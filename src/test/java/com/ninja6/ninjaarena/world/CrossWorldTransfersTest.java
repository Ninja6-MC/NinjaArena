package com.ninja6.ninjaarena.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class CrossWorldTransfersTest {

    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private final World arena = world(ArenaWorld.NAME);
    private final World lobby = world(LobbyWorld.NAME);
    private final World overworld = world("world");
    private final CrossWorldTransfers transfers = new CrossWorldTransfers();

    @Test
    void pluginWorldsAreToldApartByNameAndNullIsNeither() {
        assertTrue(PluginWorlds.isArena(arena));
        assertFalse(PluginWorlds.isLobby(arena));
        assertTrue(PluginWorlds.isLobby(lobby));
        assertFalse(PluginWorlds.isArena(lobby));
        assertTrue(PluginWorlds.isPluginWorld(arena) && PluginWorlds.isPluginWorld(lobby));
        assertFalse(PluginWorlds.isPluginWorld(overworld));
        assertTrue(PluginWorlds.isPluginWorld(new Location(lobby, 0, 64, 0)));
        assertFalse(PluginWorlds.isPluginWorld(new Location(null, 0, 64, 0)));
        assertFalse(PluginWorlds.isPluginWorld((World) null));
        assertFalse(PluginWorlds.isPluginWorld((Location) null));
        assertFalse(PluginWorlds.isPluginWorld((String) null));
        assertFalse(PluginWorlds.isArena("NinjaArena_Arenas"), "world names are case-sensitive");
    }

    @Test
    void insideTheArenaWorldEveryTeleportKeepsTheMatch() {
        assertTrue(transfers.keepsMatch(ALICE, arena, arena, false));
        assertTrue(transfers.keepsMatch(ALICE, arena, arena, true));
    }

    @Test
    void anyOtherWorldOrNoWorldEndsTheMatchEvenForAnAllowedOwnTeleport() {
        transfers.allow(player -> true);
        for (World from : new World[] {arena, lobby, overworld, null}) {
            assertFalse(transfers.keepsMatch(ALICE, from, overworld, true));
            assertFalse(transfers.keepsMatch(ALICE, from, overworld, false));
            assertFalse(transfers.keepsMatch(ALICE, from, null, true));
        }
    }

    @Test
    void todayNoTeleportIntoOrOutOfTheLobbyWorldKeepsTheMatch() {
        for (boolean own : new boolean[] {true, false}) {
            assertFalse(transfers.keepsMatch(ALICE, arena, lobby, own));
            assertFalse(transfers.keepsMatch(ALICE, lobby, arena, own));
            assertFalse(transfers.keepsMatch(ALICE, lobby, lobby, own));
            assertFalse(transfers.keepsMatch(ALICE, overworld, lobby, own));
        }
    }

    @Test
    void anAllowedPlayerCrossesOnlyByTheirOwnTeleport() {
        transfers.allow(ALICE::equals);
        assertTrue(transfers.keepsMatch(ALICE, arena, lobby, true));
        assertTrue(transfers.keepsMatch(ALICE, lobby, arena, true));
        assertTrue(transfers.keepsMatch(ALICE, lobby, lobby, true));

        assertFalse(transfers.keepsMatch(ALICE, arena, lobby, false), "another plugin's teleport");
        assertFalse(transfers.keepsMatch(ALICE, lobby, arena, false), "another plugin's teleport");
        assertFalse(transfers.keepsMatch(BOB, arena, lobby, true), "a player who is not allowed");
    }

    @Test
    void releasingARuleEndsItsCrossingAndASecondReleaseDoesNothing() {
        Runnable release = transfers.allow(player -> true);
        assertTrue(transfers.keepsMatch(ALICE, arena, lobby, true));
        release.run();
        assertFalse(transfers.keepsMatch(ALICE, arena, lobby, true));
        release.run();
        assertFalse(transfers.keepsMatch(ALICE, arena, lobby, true));
    }

    @Test
    void rulesFromSeparateFeaturesDoNotReplaceEachOther() {
        Runnable shop = transfers.allow(ALICE::equals);
        Runnable preview = transfers.allow(BOB::equals);
        assertTrue(transfers.keepsMatch(ALICE, arena, lobby, true));
        assertTrue(transfers.keepsMatch(BOB, arena, lobby, true));

        preview.run();
        assertTrue(transfers.keepsMatch(ALICE, arena, lobby, true), "the other rule still holds");
        assertFalse(transfers.keepsMatch(BOB, arena, lobby, true));
        shop.run();
        assertFalse(transfers.keepsMatch(ALICE, arena, lobby, true));
    }

    @Test
    void theSamePredicateRegisteredTwiceIsReleasedSeparately() {
        java.util.function.Predicate<UUID> everyone = player -> true;
        Runnable first = transfers.allow(everyone);
        Runnable second = transfers.allow(everyone);
        first.run();
        first.run();
        assertTrue(transfers.keepsMatch(ALICE, arena, lobby, true));
        second.run();
        assertFalse(transfers.keepsMatch(ALICE, arena, lobby, true));
    }
}
