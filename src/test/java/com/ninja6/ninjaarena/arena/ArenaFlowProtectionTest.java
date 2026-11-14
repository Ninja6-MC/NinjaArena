package com.ninja6.ninjaarena.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.LobbyWorld;
import java.lang.reflect.Proxy;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.junit.jupiter.api.Test;

/** The one exception to arena protection: lava the plugin set flowing, where it may go. */
class ArenaFlowProtectionTest {

    private static final World ARENA = world(ArenaWorld.NAME);
    private static final World LOBBY = world(LobbyWorld.NAME);
    private static final World OVERWORLD = world("world");

    /** Lava may flow only from 10, 40, 10 down to 10, 39, 10. */
    private static final ArenaProtectionListener.FlowFilter FILTER = (fx, fy, fz, tx, ty, tz) ->
            fx == 10 && fy == 40 && fz == 10 && tx == 10 && ty == 39 && tz == 10;

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName", "toString" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Block block(World world, Material type, int x, int y, int z) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getType" -> type;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T inert(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            throw new UnsupportedOperationException(m.getName());
        });
    }

    private static ArenaProtectionListener listener(boolean protecting) {
        ArenaProtectionListener listener = new ArenaProtectionListener(() -> protecting);
        listener.setFlowFilter(FILTER);
        return listener;
    }

    private static boolean cancelled(ArenaProtectionListener listener, World world, Material fluid, int fromY,
            int toY) {
        BlockFromToEvent event = new BlockFromToEvent(block(world, fluid, 10, fromY, 10),
                block(world, Material.AIR, 10, toY, 10));
        listener.onFlow(event);
        return event.isCancelled();
    }

    @Test
    void lavaTheFilterAllowsFlowsInTheArenaWorld() {
        assertFalse(cancelled(listener(true), ARENA, Material.LAVA, 40, 39));
    }

    @Test
    void everyOtherFlowInTheArenaWorldIsStillCancelled() {
        ArenaProtectionListener listener = listener(true);
        assertTrue(cancelled(listener, ARENA, Material.WATER, 40, 39), "water never flows");
        assertTrue(cancelled(listener, ARENA, Material.LAVA, 39, 38), "lava outside its footprint stays still");
        assertTrue(cancelled(listener, ARENA, Material.LAVA, 40, 41));
        BlockFromToEvent otherWorld = new BlockFromToEvent(block(ARENA, Material.LAVA, 10, 40, 10),
                block(OVERWORLD, Material.AIR, 10, 39, 10));
        listener.onFlow(otherWorld);
        assertTrue(otherWorld.isCancelled(), "never into a block of another world");
    }

    @Test
    void theLobbyWorldAllowsNoFlowAtAll() {
        assertTrue(cancelled(listener(true), LOBBY, Material.LAVA, 40, 39));
    }

    @Test
    void withoutAFilterNoLavaFlows() {
        ArenaProtectionListener listener = new ArenaProtectionListener(() -> true);
        assertTrue(cancelled(listener, ARENA, Material.LAVA, 40, 39));
        listener.setFlowFilter(FILTER);
        listener.setFlowFilter(ArenaProtectionListener.FlowFilter.NONE);
        assertTrue(cancelled(listener, ARENA, Material.LAVA, 40, 39));
    }

    @Test
    void otherWorldsAndArenaEditingAreLeftAsTheyWere() {
        assertFalse(cancelled(listener(true), OVERWORLD, Material.WATER, 40, 39));
        assertFalse(cancelled(listener(false), ARENA, Material.WATER, 40, 39), "editing lets everything flow");
    }

    @Test
    void fireAndBlockChangesByEntitiesFallingBlocksIncludedStayCancelled() {
        ArenaProtectionListener listener = listener(true);
        Block lava = block(ARENA, Material.LAVA, 10, 40, 10);
        BlockIgniteEvent ignite = new BlockIgniteEvent(lava, BlockIgniteEvent.IgniteCause.LAVA, (Entity) null);
        listener.onIgnite(ignite);
        assertTrue(ignite.isCancelled(), "lava sets nothing alight");
        BlockState state = inert(BlockState.class);
        BlockSpreadEvent spread = new BlockSpreadEvent(lava, lava, state);
        listener.onSpread(spread);
        BlockFormEvent form = new BlockFormEvent(lava, state);
        listener.onForm(form);
        @SuppressWarnings("removal")
        BlockBurnEvent burn = new BlockBurnEvent(lava);
        listener.onBurn(burn);
        for (boolean cancelled : List.of(spread.isCancelled(), form.isCancelled(), burn.isCancelled())) {
            assertTrue(cancelled);
        }
        // A falling block never lands as a block in the arena world, the plugin's own included.
        EntityChangeBlockEvent landing = new EntityChangeBlockEvent(inert(FallingBlock.class),
                block(ARENA, Material.AIR, 10, 39, 10), inert(BlockData.class));
        listener.onEntityChangeBlock(landing);
        assertEquals(true, landing.isCancelled());
    }
}
