package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerPortalsTest {

    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    /** A 2x3 opening along X; its frame stands at z = 8. */
    private static final ArenaBounds OPENING = new ArenaBounds(40, 64, 8, 41, 66, 8);

    @TempDir
    Path dir;

    private PortalRegistry registry;
    private PlayerPortals portals;

    @BeforeEach
    void setUp() throws Exception {
        registry = registry();
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        portals = new PlayerPortals(registry, PlayerPortalSettings.DEFAULTS, logger);
    }

    private PortalRegistry registry() throws Exception {
        PortalRegistry opened = new PortalRegistry(new PortalStore(dir.resolve("portals.yml")));
        opened.load();
        return opened;
    }

    static Player player(UUID id, boolean canPlay) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "Player" + id.getLeastSignificantBits();
                    case "hasPermission" -> canPlay && PortalEntry.PLAY.equals(args[0]);
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The opening shifted along X, far enough from the others for the default distance. */
    private static ArenaBounds away(int steps) {
        return new ArenaBounds(40 + steps * 100, 64, 8, 41 + steps * 100, 66, 8);
    }

    @Test
    void aLitFrameBecomesAPortalThatSurvivesARestart() throws Exception {
        PlayerPortals.Result result = portals.build(player(ALICE, true), "world", OPENING);
        assertTrue(result.built(), result.message());
        assertEquals("The portal is open. Walk through it to join the Arena.", result.message());
        Portal portal = result.portal();
        assertEquals(OPENING, portal.region());
        assertEquals(new Portal.Built(ALICE, "crying_obsidian"), portal.built());
        assertEquals(null, portal.arena());

        PortalRegistry reopened = registry();
        assertEquals(portal, reopened.at("world", 41, 65, 8));
        assertEquals(1, reopened.ownedBy(ALICE));
    }

    @Test
    void onlyPlayersWithThePlayNodeBuild() {
        PlayerPortals.Result result = portals.build(player(ALICE, false), "world", OPENING);
        assertFalse(result.built());
        assertEquals("You do not have permission to build arena portals.", result.message());
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void onlyAllowedWorldsAndNeverTheArenaWorld() {
        assertEquals(Optional.of("Arena portals cannot be built in this world."),
                portals.refusal(player(ALICE, true), "world_nether", OPENING));
        portals.setSettings(new PlayerPortalSettings(Set.of("world", "world_nether"), Material.CRYING_OBSIDIAN, 2, 32,
                0x40E0D0));
        assertEquals(Optional.empty(), portals.refusal(player(ALICE, true), "world_nether", OPENING));
        assertFalse(portals.settings().allows(ArenaWorld.NAME));
        assertEquals(Optional.of("Arena portals cannot be built in this world."),
                portals.refusal(player(ALICE, true), ArenaWorld.NAME, OPENING));

        portals.setSettings(PlayerPortalSettings.OFF);
        assertFalse(portals.build(player(ALICE, true), "world", OPENING).built());
    }

    @Test
    void eachPlayerOwnsAtMostTheLimit() {
        assertTrue(portals.build(player(ALICE, true), "world", away(0)).built());
        assertTrue(portals.build(player(ALICE, true), "world", away(1)).built());
        PlayerPortals.Result third = portals.build(player(ALICE, true), "world", away(2));
        assertFalse(third.built());
        assertEquals("You already have 2 arena portals, the most one player may build. Break one to build another.",
                third.message());
        assertTrue(portals.build(player(BOB, true), "world", away(2)).built(), "the limit is per player");

        portals.setSettings(new PlayerPortalSettings(Set.of("world"), Material.CRYING_OBSIDIAN, 0, 32, 0x40E0D0));
        assertTrue(portals.build(player(ALICE, true), "world", away(3)).built(), "0 is no limit");
        portals.setSettings(new PlayerPortalSettings(Set.of("world"), Material.CRYING_OBSIDIAN, 1, 32, 0x40E0D0));
        assertEquals("You already have 1 arena portal, the most one player may build. Break one to build another.",
                portals.build(player(BOB, true), "world", away(4)).message());
    }

    @Test
    void portalsKeepTheirDistanceFromEveryPortalInTheWorld() throws Exception {
        registry.create("lobby", "world", new ArenaBounds(60, 64, 8, 62, 67, 8), "dojo");
        PlayerPortals.Result near = portals.build(player(ALICE, true), "world", OPENING);
        assertFalse(near.built());
        assertEquals("This is 20 blocks from another arena portal. Portals must be at least 32 blocks apart.",
                near.message());
        assertTrue(portals.build(player(ALICE, true), "world_nether", OPENING).message().contains("this world"));
        assertTrue(portals.build(player(ALICE, true), "world", new ArenaBounds(94, 64, 8, 95, 66, 8)).built());

        portals.setSettings(new PlayerPortalSettings(Set.of("world"), Material.CRYING_OBSIDIAN, 2, 0, 0x40E0D0));
        assertTrue(portals.build(player(ALICE, true), "world", OPENING).built(), "0 turns the check off");
        assertEquals(Optional.of("This frame is already open."), portals.refusal(player(BOB, true), "world", OPENING));
    }

    @Test
    void breakingAFrameBlockRemovesThePortalButNothingElseDoes() {
        Portal portal = portals.build(player(ALICE, true), "world", OPENING).portal();
        Portal other = portals.build(player(BOB, true), "world", away(1)).portal();
        // Corners, the opening, blocks beside the frame and other worlds leave it alone.
        int[][] harmless = {{39, 63, 8}, {42, 67, 8}, {40, 65, 8}, {40, 63, 9}, {39, 65, 7}, {38, 65, 8}};
        for (int[] block : harmless) {
            assertTrue(portals.frameBlockGone("world", block[0], block[1], block[2]).isEmpty(),
                    block[0] + "," + block[1] + "," + block[2]);
        }
        assertTrue(portals.frameBlockGone("world_nether", 39, 65, 8).isEmpty());
        assertEquals(portal, registry.get(portal.name()).orElseThrow());

        assertEquals(Set.of(portal), portals.frameBlockGone("world", 39, 65, 8));
        assertTrue(registry.get(portal.name()).isEmpty());
        assertTrue(portals.frameBlockGone("world", 39, 65, 8).isEmpty(), "only once");
        assertEquals(other, registry.get(other.name()).orElseThrow());
        assertEquals(Set.of(other), portals.frameBlockGone("world", 140, 67, 8), "the top counts too");
    }

    @Test
    void anAdminPortalHasNoFrameToBreak() throws Exception {
        registry.create("lobby", "world", new ArenaBounds(60, 64, 8, 62, 67, 8), "dojo");
        assertTrue(portals.frameBlockGone("world", 59, 65, 8).isEmpty());
        assertTrue(registry.get("lobby").isPresent());
    }

    @Test
    void aCheckRemovesAPortalWhoseFrameWentWhileUnloadedFramesStay() {
        BlockWorld world = new BlockWorld("world").frame(true, 40, 64, 8, 2, 3, Material.CRYING_OBSIDIAN);
        Portal portal = portals.build(player(ALICE, true), "world", OPENING).portal();
        assertTrue(portals.verify(portal, world.world));

        world.set(42, 65, 8, Material.STONE);
        world.unload(2, 0);
        assertTrue(portals.verify(portal, world.world), "an unloaded frame block counts as there");
        assertTrue(registry.get(portal.name()).isPresent());

        world.unloaded.clear();
        assertFalse(portals.verify(portal, world.world));
        assertTrue(registry.get(portal.name()).isEmpty());
    }

    @Test
    void aPortalKeepsItsFrameBlockWhenTheSettingChanges() {
        BlockWorld world = new BlockWorld("world").frame(true, 40, 64, 8, 2, 3, Material.CRYING_OBSIDIAN);
        Portal portal = portals.build(player(ALICE, true), "world", OPENING).portal();
        portals.setSettings(new PlayerPortalSettings(Set.of("world"), Material.GILDED_BLACKSTONE, 2, 32, 0x40E0D0));
        assertTrue(portals.verify(portal, world.world));
        assertEquals("gilded_blackstone", portals.build(player(BOB, true), "world", away(1)).portal().built().frame());
    }
}
