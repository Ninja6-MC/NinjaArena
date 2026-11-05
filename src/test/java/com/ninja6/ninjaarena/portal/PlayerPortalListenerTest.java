package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.support.TestItemStack;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ExplosionResult;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerPortalListenerTest {

    private static final UUID ALICE = new UUID(0, 1);
    private static final ArenaBounds OPENING = new ArenaBounds(40, 64, 8, 41, 66, 8);

    @TempDir
    Path dir;

    private final List<String> messages = new ArrayList<>();
    private final List<EquipmentSlot> worn = new ArrayList<>();
    private BlockWorld world;
    private PortalRegistry registry;
    private PlayerPortals portals;
    private PlayerPortalListener listener;
    private boolean canPlay = true;
    private GameMode gameMode = GameMode.SURVIVAL;

    @BeforeEach
    void setUp() throws Exception {
        world = new BlockWorld("world").frame(true, 40, 64, 8, 2, 3, Material.CRYING_OBSIDIAN);
        registry = new PortalRegistry(new PortalStore(dir.resolve("portals.yml")));
        registry.load();
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        portals = new PlayerPortals(registry, PlayerPortalSettings.DEFAULTS, logger);
        listener = new PlayerPortalListener(portals, registry);
    }

    private Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ALICE;
                    case "getName" -> "Alice";
                    case "hasPermission" -> canPlay && PortalEntry.PLAY.equals(args[0]);
                    case "getGameMode" -> gameMode;
                    case "sendMessage" -> {
                        messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    case "damageItemStack" -> {
                        worn.add((EquipmentSlot) args[0]);
                        yield null;
                    }
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Uses the item on a face of the block, as a right click does. */
    private PlayerInteractEvent use(Material item, Block clicked, BlockFace face) {
        PlayerInteractEvent event = new PlayerInteractEvent(player(), Action.RIGHT_CLICK_BLOCK,
                new TestItemStack(item, "", 1), clicked, face, EquipmentSlot.HAND);
        listener.onIgnite(event);
        return event;
    }

    @Test
    void flintAndSteelOnTheFrameOpensAPortalInsteadOfAFire() {
        PlayerInteractEvent event = use(Material.FLINT_AND_STEEL, world.block(40, 63, 8), BlockFace.UP);
        assertEquals(Event.Result.DENY, event.useItemInHand(), "no fire is placed");
        Portal portal = registry.at("world", 41, 66, 8);
        assertEquals(OPENING, portal.region());
        assertEquals(ALICE, portal.built().owner());
        assertEquals(List.of("The portal is open. Walk through it to join the Arena."), messages);
        assertEquals(List.of(EquipmentSlot.HAND), worn);
        assertEquals(List.of("minecraft:item.flintandsteel.use"), world.sounds);

        // Lighting it again changes nothing and is left to vanilla.
        PlayerInteractEvent again = use(Material.FLINT_AND_STEEL, world.block(41, 63, 8), BlockFace.UP);
        assertEquals(Event.Result.DEFAULT, again.useItemInHand());
        assertEquals(1, registry.all().size());
    }

    @Test
    void aWallBehindTheOpeningLightsItTooAndCreativeDoesNotWearTheTool() {
        gameMode = GameMode.CREATIVE;
        use(Material.FLINT_AND_STEEL, world.block(41, 65, 9), BlockFace.NORTH);
        assertEquals(1, registry.all().size());
        assertTrue(worn.isEmpty());
    }

    @Test
    void aFrameAlongZOpensToo() {
        world = new BlockWorld("world").frame(false, 20, 70, 5, 3, 4, Material.CRYING_OBSIDIAN);
        use(Material.FLINT_AND_STEEL, world.block(5, 69, 21), BlockFace.UP);
        assertEquals(new ArenaBounds(5, 70, 20, 5, 73, 22), registry.at("world", 5, 71, 21).region());
    }

    @Test
    void anIncompleteOrOtherFrameIsLeftToVanilla() {
        world.set(42, 65, 8, Material.AIR);
        PlayerInteractEvent event = use(Material.FLINT_AND_STEEL, world.block(40, 63, 8), BlockFace.UP);
        assertEquals(Event.Result.DEFAULT, event.useItemInHand());
        assertTrue(registry.all().isEmpty());
        assertTrue(messages.isEmpty());

        world = new BlockWorld("world").frame(true, 40, 64, 8, 2, 3, Material.OBSIDIAN);
        event = use(Material.FLINT_AND_STEEL, world.block(40, 63, 8), BlockFace.UP);
        assertEquals(Event.Result.DEFAULT, event.useItemInHand(), "a nether portal stays a nether portal");
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void otherItemsAndDeniedUsesDoNothing() {
        use(Material.DIAMOND, world.block(40, 63, 8), BlockFace.UP);
        assertTrue(registry.all().isEmpty());

        PlayerInteractEvent denied = new PlayerInteractEvent(player(), Action.RIGHT_CLICK_BLOCK,
                new TestItemStack(Material.FLINT_AND_STEEL, "", 1), world.block(40, 63, 8), BlockFace.UP,
                EquipmentSlot.HAND);
        denied.setCancelled(true);
        listener.onIgnite(denied);
        assertTrue(registry.all().isEmpty());
        assertTrue(messages.isEmpty());
    }

    @Test
    void aRefusedPlayerIsToldWhyAndNoFireIsPlaced() {
        canPlay = false;
        PlayerInteractEvent event = use(Material.FLINT_AND_STEEL, world.block(40, 63, 8), BlockFace.UP);
        assertEquals(Event.Result.DENY, event.useItemInHand());
        assertEquals(List.of("You do not have permission to build arena portals."), messages);
        assertTrue(registry.all().isEmpty());
        assertTrue(worn.isEmpty());

        canPlay = true;
        messages.clear();
        world = new BlockWorld("world_nether").frame(true, 40, 64, 8, 2, 3, Material.CRYING_OBSIDIAN);
        use(Material.FLINT_AND_STEEL, world.block(40, 63, 8), BlockFace.UP);
        assertEquals(List.of("Arena portals cannot be built in this world."), messages);
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void breakingBlowingUpOrPushingAFrameBlockRemovesThePortal() {
        use(Material.FLINT_AND_STEEL, world.block(40, 63, 8), BlockFace.UP);
        listener.onBreak(new BlockBreakEvent(world.block(39, 63, 8), player()));
        listener.onBreak(new BlockBreakEvent(world.block(40, 63, 9), player()));
        assertEquals(1, registry.all().size(), "a corner or a block beside the frame leaves it");
        listener.onBreak(new BlockBreakEvent(world.block(42, 66, 8), player()));
        assertTrue(registry.all().isEmpty());

        use(Material.FLINT_AND_STEEL, world.block(40, 63, 8), BlockFace.UP);
        Entity creeper = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[] {Entity.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        listener.onEntityExplode(new EntityExplodeEvent(creeper, world.at(40, 70, 8),
                List.of(world.block(10, 64, 8), world.block(41, 67, 8)), 1, ExplosionResult.DESTROY));
        assertTrue(registry.all().isEmpty());

        use(Material.FLINT_AND_STEEL, world.block(40, 63, 8), BlockFace.UP);
        listener.onPistonExtend(new BlockPistonExtendEvent(world.block(38, 65, 8), List.of(world.block(39, 65, 8)),
                BlockFace.EAST));
        assertTrue(registry.all().isEmpty());
    }
}
