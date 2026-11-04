package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortalCurtainsTest {

    private static final ArenaBounds OPENING = new ArenaBounds(40, 64, 8, 41, 66, 8);

    @TempDir
    Path dir;

    private final List<Location> players = new ArrayList<>();
    private final List<String> drawn = new ArrayList<>();
    private final List<String> hidden = new ArrayList<>();
    private BlockWorld world;
    private PortalRegistry registry;
    private PlayerPortals rules;
    private PortalCurtains curtains;
    private long tick;
    private boolean closed;

    @BeforeEach
    void setUp() throws Exception {
        world = new BlockWorld("world").frame(true, 40, 64, 8, 2, 3, Material.CRYING_OBSIDIAN);
        registry = new PortalRegistry(new PortalStore(dir.resolve("portals.yml")));
        registry.load();
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        rules = new PlayerPortals(registry, PlayerPortalSettings.DEFAULTS, logger);
        PortalSurface surface = new PortalSurface() {
            @Override
            public void draw(World in, Portal portal) {
                drawn.add(portal.name());
            }

            @Override
            public void hide(Portal portal) {
                hidden.add(portal.name());
            }

            @Override
            public void close() {
                closed = true;
            }
        };
        World arenaWorld = new BlockWorld(ArenaWorld.NAME).world;
        Map<String, World> worlds = Map.of("world", world.world, ArenaWorld.NAME, arenaWorld);
        curtains = new PortalCurtains(registry, rules, this::online, worlds::get, surface, () -> tick);
    }

    private List<Player> online() {
        List<Player> list = new ArrayList<>();
        for (Location location : players) {
            list.add((Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getLocation" -> location.clone();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    }));
        }
        return list;
    }

    private Portal build(ArenaBounds opening) throws Exception {
        return registry.createBuilt("world", opening, new Portal.Built(new UUID(0, 1), "crying_obsidian"));
    }

    private void pass() {
        curtains.run();
        tick += PortalCurtains.PERIOD_TICKS;
    }

    @Test
    void onlyPlayerBuiltPortalsNearAPlayerAreDrawn() throws Exception {
        build(OPENING);
        registry.create("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");
        pass();
        assertTrue(drawn.isEmpty(), "nobody is online");

        players.add(world.at(300, 64, 300));
        players.add(new Location(new BlockWorld(ArenaWorld.NAME).world, 40, 64, 8));
        pass();
        assertTrue(drawn.isEmpty(), "too far, or in the arena world");

        players.add(world.at(60, 64, 30));
        pass();
        pass();
        assertEquals(List.of("built-1", "built-1"), drawn);
    }

    @Test
    void aPlayerWhoWalksAwayHidesTheSurfaceAndClosingHidesTheRest() throws Exception {
        build(OPENING);
        players.add(world.at(41, 64, 0));
        pass();
        players.clear();
        pass();
        assertEquals(List.of("built-1"), hidden);
        assertEquals(1, drawn.size());

        players.add(world.at(41, 64, 0));
        pass();
        curtains.close();
        assertEquals(List.of("built-1", "built-1"), hidden);
        assertTrue(closed);
    }

    @Test
    void theFrameIsCheckedOnFirstSightAndThenEveryFewSeconds() throws Exception {
        build(OPENING);
        players.add(world.at(41, 64, 0));
        pass();
        world.set(39, 65, 8, Material.AIR);
        for (long waited = PortalCurtains.PERIOD_TICKS; waited < PortalCurtains.CHECK_TICKS;
                waited += PortalCurtains.PERIOD_TICKS) {
            pass();
        }
        assertTrue(registry.get("built-1").isPresent(), "not checked again yet");
        pass();
        assertTrue(registry.get("built-1").isEmpty());
        assertEquals(List.of("built-1"), hidden);
        int draws = drawn.size();
        pass();
        assertEquals(draws, drawn.size());

        // A portal whose frame broke while nobody watched goes the first time someone comes near.
        build(new ArenaBounds(140, 64, 8, 141, 66, 8));
        players.set(0, world.at(141, 64, 0));
        pass();
        assertTrue(registry.get("built-1").isEmpty());
        assertEquals(draws, drawn.size());
    }

    @Test
    void anUnloadedChunkOrWorldIsNeitherDrawnNorChecked() throws Exception {
        build(OPENING);
        world.set(39, 65, 8, Material.AIR);
        players.add(world.at(41, 64, 0));
        world.unload(2, 0);
        pass();
        assertTrue(drawn.isEmpty());
        assertTrue(registry.get("built-1").isPresent());

        registry.createBuilt("world_nether", OPENING, new Portal.Built(new UUID(0, 1), "crying_obsidian"));
        players.add(new Location(new BlockWorld("world_nether").world, 41, 64, 0));
        pass();
        assertTrue(drawn.isEmpty());
    }

    @Test
    void aPassDrawsAtMostTheCapAndARemovedPortalIsHidden() throws Exception {
        for (int i = 0; i < 20; i++) {
            build(new ArenaBounds(i * 3, 64, 8, i * 3 + 1, 66, 8));
            world.frame(true, i * 3, 64, 8, 2, 3, Material.CRYING_OBSIDIAN);
        }
        players.add(world.at(30, 64, 8));
        pass();
        assertEquals(PortalCurtains.MAX_PORTALS, drawn.size());
        assertEquals(PortalCurtains.MAX_PORTALS, curtains.shown());

        String first = drawn.getFirst();
        registry.delete(first);
        assertEquals(List.of(first), hidden);
    }
}
