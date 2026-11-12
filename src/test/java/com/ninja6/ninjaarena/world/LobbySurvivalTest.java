package com.ninja6.ninjaarena.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.lobby.LobbyBuild;
import com.ninja6.ninjaarena.lobby.LobbyLayout;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.junit.jupiter.api.Test;

/** The lobby build outlives restarts and every retirement of the arena world. */
class LobbySurvivalTest {

    private final List<String> lobbyCalls = new ArrayList<>();
    private final List<String> siteCalls = new ArrayList<>();
    /** The marker the lobby world keeps in its own saved data. */
    private boolean marked;

    private final World lobby = world(LobbyWorld.NAME, lobbyCalls);

    private final LobbyBuild.Site site = new LobbyBuild.Site() {
        @Override
        public boolean placed() {
            return marked;
        }

        @Override
        public void markPlaced() {
            marked = true;
            siteCalls.add("mark");
        }

        @Override
        public void clear(ArenaBounds box) {
            siteCalls.add("clear");
        }

        @Override
        public void place(int x, int y, int z) {
            siteCalls.add("place");
        }
    };

    private static World world(String name, List<String> calls) {
        WorldBorder border = (WorldBorder) Proxy.newProxyInstance(WorldBorder.class.getClassLoader(),
                new Class<?>[] {WorldBorder.class}, (proxy, method, args) -> null);
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUID" -> new UUID(0, name.length());
                    case "getPlayers" -> List.of();
                    case "getForceLoadedChunks" -> Set.of();
                    case "getPluginChunkTickets" -> Map.of();
                    case "getWorldBorder" -> border;
                    case "setGameRule" -> true;
                    case "setAutoSave" -> { calls.add("autosave " + args[0]); yield null; }
                    case "save" -> { calls.add("save"); yield null; }
                    case "setViewDistance", "setSimulationDistance", "setTime", "setStorm", "setThundering" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Logger quiet() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return logger;
    }

    /** One server start: the lobby world is loaded and the build placed only if it is new. */
    private boolean start() {
        new LobbyWorld(lobby);
        return new LobbyBuild(LobbyLayout.bundled(), site, quiet()).placeIfNew();
    }

    @Test
    void theLobbyIsPlacedOnceAndSurvivesRestartsAndArenaWorldRetires() {
        assertTrue(start(), "a new lobby world gets the build");
        assertEquals(List.of("place", "mark"), siteCalls);
        siteCalls.clear();

        List<String> arenaCalls = new ArrayList<>();
        World arena = world(ArenaWorld.NAME, arenaCalls);
        List<World> unloaded = new ArrayList<>();
        var arenaWorld = new ArenaWorld(arena, () -> arena, world -> {
            unloaded.add(world);
            return true;
        });
        for (int retire = 0; retire < 3; retire++) {
            assertTrue(arenaWorld.releaseIfIdle(() -> true));
            assertSame(arena, arenaWorld.world());
        }
        assertEquals(List.of(arena, arena, arena), unloaded, "only the arena world is ever retired");

        assertFalse(start(), "a restart keeps the lobby world as it is");
        assertFalse(start());
        assertEquals(List.of(), siteCalls, "nothing was cleared or placed again");
        assertFalse(lobbyCalls.contains("autosave false"), "the lobby world is never left unsaved");
        assertTrue(lobbyCalls.stream().allMatch("autosave true"::equals), lobbyCalls.toString());
    }
}
