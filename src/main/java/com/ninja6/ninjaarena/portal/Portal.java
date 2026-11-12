package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.message.KeyedArgumentException;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A box of blocks in a world outside the arena world that sends a player who walks into it
 * to an arena's waiting room, the shared hub or a live match. Whatever is built around the box is decoration; only the box
 * counts. Instances are immutable.
 *
 * @param world  the world's name
 * @param region both corners inclusive; at most {@link #MAX_SPAN} blocks along each axis
 * @param arena  the arena's name; the arena may have been deleted since, which makes the
 *               portal refuse everyone until it is bound again. A portal that leads to the
 *               shared hub only uses it while its lobby is dedicated. {@code null} for a
 *               player-built portal, which leads to whichever arena is open when a player
 *               walks in ({@link #boundTo})
 * @param lobby  the portal's own lobby choice, or {@code null} to use the server default;
 *               always {@code null} for a spectator portal, which leads to a live match, and
 *               for a player-built one
 * @param built  who built the portal and from what, or {@code null} for a portal an admin
 *               marked out
 */
public record Portal(String name, String world, ArenaBounds region, String arena, boolean spectator,
        LobbyMode lobby, Built built) {

    /**
     * Who built a player-built portal, whose region is the opening of the frame they lit.
     *
     * @param owner the player who lit it, for the per-player limit
     * @param frame the frame block as a lower-case material name. The portal keeps the block
     *              it was built from when the configured frame block changes
     */
    public record Built(UUID owner, String frame) {

        public Built {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(frame, "frame");
            if (!frame.matches("[a-z0-9_]{1,64}")) {
                throw new KeyedArgumentException("invalid frame block " + frame,
                        "portal.invalid.frame", "frame", frame);
            }
        }
    }

    public Portal(String name, String world, ArenaBounds region, String arena) {
        this(name, world, region, arena, false);
    }

    public Portal(String name, String world, ArenaBounds region, String arena, boolean spectator) {
        this(name, world, region, arena, spectator, null);
    }

    public Portal(String name, String world, ArenaBounds region, String arena, boolean spectator,
            LobbyMode lobby) {
        this(name, world, region, arena, spectator, lobby, null);
    }

    /** A player-built portal, bound to no arena. */
    public static Portal built(String name, String world, ArenaBounds opening, Built built) {
        return new Portal(name, world, opening, null, false, null, Objects.requireNonNull(built, "built"));
    }

    /** The same portal with another lobby choice; {@code null} uses the server default. */
    public Portal withLobby(LobbyMode lobby) {
        return new Portal(name, world, region, arena, spectator, lobby, built);
    }

    /**
     * The player-built portal bound to the arena it sends one player to. Only that entry uses
     * the copy; the registry keeps the unbound portal.
     */
    public Portal boundTo(String arena) {
        if (built == null) {
            throw new IllegalStateException("only a player-built portal is bound per entry");
        }
        return new Portal(name, world, region, Objects.requireNonNull(arena, "arena"), false, null, built);
    }

    /** Whether a player built this portal from a frame. */
    public boolean playerBuilt() {
        return built != null;
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
        if (!isValidName(name)) {
            throw new KeyedArgumentException("invalid portal name " + name, "portal.invalid.name", "name", name);
        }
        if (world.isBlank()) {
            throw new KeyedArgumentException("world must not be blank", "portal.invalid.world");
        }
        if (world.equals(ArenaWorld.NAME)) {
            throw new KeyedArgumentException("portals cannot be in the arena world", "portal.invalid.arena-world");
        }
        if (arena == null ? built == null : !Arena.isValidName(arena)) {
            throw new KeyedArgumentException("invalid arena name " + arena, "arena.invalid.name", "name", arena);
        }
        if (spectator && lobby != null) {
            throw new KeyedArgumentException("a spectator portal leads to a live match and has no lobby",
                    "portal.invalid.spectator-lobby");
        }
        if (built != null && (spectator || lobby != null)) {
            throw new KeyedArgumentException("a player-built portal follows the server default lobby",
                    "portal.invalid.built-lobby");
        }
        if (span(region.minX(), region.maxX()) > MAX_SPAN || span(region.minY(), region.maxY()) > MAX_SPAN
                || span(region.minZ(), region.maxZ()) > MAX_SPAN) {
            throw new KeyedArgumentException("a portal can be at most " + MAX_SPAN + " blocks along each axis",
                    "portal.invalid.too-large", "max", MAX_SPAN);
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

    /** How far apart the centres of the two regions are, in blocks. */
    public double distanceTo(Portal other) {
        return Math.sqrt(square(centre(region.minX(), region.maxX()) - centre(other.region.minX(), other.region.maxX()))
                + square(centre(region.minY(), region.maxY()) - centre(other.region.minY(), other.region.maxY()))
                + square(centre(region.minZ(), region.maxZ()) - centre(other.region.minZ(), other.region.maxZ())));
    }

    private static double centre(int min, int max) {
        return ((double) min + max + 1) / 2;
    }

    private static double square(double value) {
        return value * value;
    }

    private static long span(int min, int max) {
        return (long) max - min + 1;
    }
}
