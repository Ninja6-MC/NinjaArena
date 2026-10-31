package com.ninja6.ninjaarena.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureArenaCopiesTest {

    @TempDir
    Path dir;
    private final List<String> calls = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();
    private final List<Entity> removed = new ArrayList<>();
    private final Chunk chunk = proxy(Chunk.class, (object, method, args) -> {
        assertEquals("getEntities", method.getName());
        calls.add("entities loaded");
        return entities.toArray(Entity[]::new);
    });
    private final World world = proxy(World.class, (object, method, args) -> {
        assertEquals("getChunkAt", method.getName());
        assertEquals(List.of(-1, 0), List.of(args));
        calls.add("chunk loaded");
        return chunk;
    });

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private Entity entity(Class<? extends Entity> type, double x) {
        Entity entity = proxy(type, (object, method, args) -> switch (method.getName()) {
            case "getLocation" -> new Location(world, x, 1, 1);
            case "remove" -> { removed.add((Entity) object); calls.add("removed"); yield null; }
            case "equals" -> object == args[0];
            case "hashCode" -> System.identityHashCode(object);
            default -> throw new UnsupportedOperationException(method.getName());
        });
        entities.add(entity);
        return entity;
    }

    @Test
    void restoreLoadsEntitiesBeforePlacingAndPreservesDecorationsAndOutsideEntities() throws Exception {
        Path copy = Files.createDirectories(dir.resolve("dojo"));
        Files.createFile(copy.resolve("-16_0_0.nbt"));
        List<Entity> transientEntities = new ArrayList<>();
        for (Class<? extends Entity> type : List.of(Mob.class, Item.class, Projectile.class, ExperienceOrb.class)) {
            transientEntities.add(entity(type, -1));
        }
        for (Class<? extends Entity> type : List.of(Player.class, ArmorStand.class, Hanging.class, Display.class,
                Marker.class)) {
            entity(type, -1);
        }
        entity(Mob.class, 0); // The next column starts here, outside the restored half-open bounds.
        Structure structure = proxy(Structure.class, (object, method, args) -> switch (method.getName()) {
            case "getSize" -> new BlockVector(16, 64, 16);
            case "place" -> {
                assertEquals(transientEntities, removed);
                assertEquals(-16, ((Location) args[0]).getBlockX());
                calls.add("placed");
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        StructureManager manager = proxy(StructureManager.class, (object, method, args) -> {
            assertEquals("loadStructure", method.getName());
            return structure;
        });
        new StructureArenaCopies(dir, world, manager).restore("dojo");
        assertEquals(transientEntities, removed);
        assertEquals(List.of("chunk loaded", "entities loaded"), calls.subList(0, 2));
        assertEquals("placed", calls.getLast());
        assertTrue(Files.exists(copy.resolve("-16_0_0.nbt")));
    }
}
