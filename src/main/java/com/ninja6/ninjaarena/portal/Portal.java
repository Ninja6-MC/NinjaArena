package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.Locale;
import java.util.Objects;

/**
 * A box of blocks in a world outside the arena world that sends a player who walks into it
 * to an arena's waiting room, the shared hub or a live match. Whatever is built around the box is decoration; only the box
 * counts. Instances are immutable.
 *
 * @param world  the world's name
 * @param region both corners inclusive; at most {@link #MAX_SPAN} blocks along each axis
 * @param arena  the arena's name; the arena may have been deleted since, which makes the
 *               portal refuse everyone until it is bound again. A portal that leads to the
 *               shared hub only uses it while its lobby is dedicated
 * @param lobby  the portal's own lobby choice, or {@code null} to use the server default;
 *               always {@code null} for a spectator portal, which leads to a live match
 */
public record Portal(String name, String world, ArenaBounds region, String arena, boolean spectator,
        LobbyMode lobby) {

    public Portal(String name, String world, ArenaBounds region, String arena) {
        this(name, world, region, arena, false);
    }

    public Portal(String name, String world, ArenaBounds region, String arena, boolean spectator) {
        this(name, world, region, arena, spectator, null);
    }

    /** The same portal with another lobby choice; {@code null} uses the server default. */
    public Portal withLobby(LobbyMode lobby) {
        return new Portal(name, world, region, arena, spectator, lobby);
    }

    /** The lobby this portal leads to, given the server default. */
    public LobbyMode lobby(LobbyMode serverDefault) {
        return lobby == null ? serverDefault : lobby;
    }

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
        if (spectator && lobby != null) {
            throw new IllegalArgumentException("a spectator portal leads to a live match and has no lobby");
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
