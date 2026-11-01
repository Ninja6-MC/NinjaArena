package com.ninja6.ninjaarena.world;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import org.bukkit.Material;
import org.bukkit.World;

/** Settles the edit-to-gameplay boundary without executing queued piston block events. */
public final class PaperBlockEvents {
    private PaperBlockEvents() {
    }

    /** A moving piston already has changed blocks; let editing finish its motion first. */
    public static void finishEdit(World world) {
        for (var chunk : world.getLoadedChunks()) {
            for (var state : chunk.getTileEntities()) {
                if (state.getType() == Material.MOVING_PISTON) {
                    throw new IllegalStateException("A piston is still moving; wait for it to stop before finishing the edit");
                }
            }
        }
        clear(world);
    }

    /**
     * Paper's sticky retract event is too late to protect the head/base. Discard queued
     * block actions before leaving editing, and after placing authoritative copies.
     * No chunk, entity or POI saving or unload internals are invoked.
     */
    public static void clear(World world) {
        try {
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel", false,
                    level.getClass().getClassLoader());
            if (!serverLevel.isInstance(level)) {
                throw new ReflectiveOperationException("Unsupported Paper world handle");
            }
            Class<?> chunkLevel = Class.forName("net.minecraft.server.level.ChunkLevel", true,
                    level.getClass().getClassLoader());
            int radius = chunkLevel.getField("RADIUS_AROUND_FULL_CHUNK").getInt(null);
            if (radius < 0 || radius > 16) {
                throw new ReflectiveOperationException("Unsupported Paper chunk dependency radius: " + radius);
            }
            clear(level, serverLevel);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Cannot freeze arena block actions on this server version", failure);
        }
    }

    static void clear(Object level, Class<?> serverLevel) throws ReflectiveOperationException {
        Field queued = serverLevel.getDeclaredField("blockEvents");
        Field deferred = serverLevel.getDeclaredField("blockEventsToReschedule");
        for (Field field : new Field[] {queued, deferred}) {
            if (Modifier.isStatic(field.getModifiers()) || !Collection.class.isAssignableFrom(field.getType())) {
                throw new ReflectiveOperationException("Unsupported Paper block-event queue layout");
            }
            field.setAccessible(true);
        }
        // Validate both queues before changing either one.
        Collection<?> first = (Collection<?>) queued.get(level);
        Collection<?> second = (Collection<?>) deferred.get(level);
        if (first == null || second == null) {
            throw new ReflectiveOperationException("Missing Paper block-event queue");
        }
        first.clear();
        second.clear();
    }
}
