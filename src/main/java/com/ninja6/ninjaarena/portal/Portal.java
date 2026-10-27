package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.Locale;
import java.util.Objects;

/**
 * A box of blocks in a world outside the arena world that sends a player who walks into it
 * to an arena's waiting room. Whatever is built around the box is decoration; only the box
 * counts. Instances are immutable.
 *
 * @param world  the world's name
 * @param region both corners inclusive; at most {@link #MAX_SPAN} blocks along each axis
 * @param arena  the arena's name; the arena may have been deleted since, which makes the
 *               portal refuse everyone until it is bound again
 */
public record Portal(String name, String world, ArenaBounds region, String arena) {

    /** The longest a portal may be along any axis, which keeps the chunk index small. */
    public static final int MAX_SPAN = 128;

    public Portal {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(arena, "arena");
        if (!isValidName(name)) {
            throw new IllegalArgumentException("invalid portal name " + name);
        }
        if (world.isBlank()) {
            throw new IllegalArgumentException("world must not be blank");
        }
        if (world.equals(ArenaWorld.NAME)) {
            throw new IllegalArgumentException("portals cannot be in the arena world");
        }
        if (!Arena.isValidName(arena)) {
            throw new IllegalArgumentException("invalid arena name " + arena);
        }
        if (span(region.minX(), region.maxX()) > MAX_SPAN || span(region.minY(), region.maxY()) > MAX_SPAN
                || span(region.minZ(), region.maxZ()) > MAX_SPAN) {
            throw new IllegalArgumentException("a portal can be at most " + MAX_SPAN + " blocks along each axis");
        }
    }

    /** Lower case letters, digits, {@code _} and {@code -}, 1 to 32 characters, as for arenas. */
    public static boolean isValidName(String name) {
        return Arena.isValidName(name);
    }

    /** Portal names are case-insensitive; this is the stored form. */
    public static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName) && region.contains(x, y, z);
    }

    /** Whether the two portals share at least one block. */
    public boolean overlaps(Portal other) {
        ArenaBounds a = region;
        ArenaBounds b = other.region;
        return world.equals(other.world)
                && a.minX() <= b.maxX() && b.minX() <= a.maxX()
                && a.minY() <= b.maxY() && b.minY() <= a.maxY()
                && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
    }

    private static long span(int min, int max) {
        return (long) max - min + 1;
    }
}
