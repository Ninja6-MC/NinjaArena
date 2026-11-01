package com.ninja6.ninjaarena.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.lang.reflect.Proxy;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.junit.jupiter.api.Test;

class PaperBlockEventsTest {
    static final class Queues {
        private final Collection<String> blockEvents = new ArrayList<>(List.of("sticky retract"));
        private final List<String> blockEventsToReschedule = new ArrayList<>(List.of("deferred extend"));
    }

    static final class Invalid {
        private final Collection<String> blockEvents = new ArrayList<>(List.of("must remain"));
        private final String blockEventsToReschedule = "unsupported";
    }

    @Test
    void clearsBothImmediateAndDeferredQueues() throws Exception {
        var queues = new Queues();
        PaperBlockEvents.clear(queues, Queues.class);
        assertEquals(List.of(), List.copyOf(queues.blockEvents));
        assertEquals(List.of(), queues.blockEventsToReschedule);
    }

    @Test
    void validatesBothQueuesBeforeChangingEither() {
        var queues = new Invalid();
        assertThrows(ReflectiveOperationException.class, () -> PaperBlockEvents.clear(queues, Invalid.class));
        assertEquals(List.of("must remain"), List.copyOf(queues.blockEvents));
    }

    @Test
    void inFlightPistonRefusesEditCompletionBeforeNativeQueuesAreTouched() {
        BlockState state = (BlockState) Proxy.newProxyInstance(BlockState.class.getClassLoader(),
                new Class<?>[] {BlockState.class}, (o, m, a) -> Material.MOVING_PISTON);
        Chunk chunk = (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(),
                new Class<?>[] {Chunk.class}, (o, m, a) -> new BlockState[] {state});
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class}, (o, m, a) -> new Chunk[] {chunk});
        var failure = assertThrows(IllegalStateException.class, () -> PaperBlockEvents.finishEdit(world));
        assertEquals("A piston is still moving; wait for it to stop before finishing the edit", failure.getMessage());
    }
}
