package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.portal.PortalFrame.Cell;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * The rules for portals players build: who may light one and where, the per-player limit,
 * the distance between portals, and removing a portal whose frame is gone. The portals
 * themselves live in the {@link PortalRegistry} beside the admin ones, so they persist in
 * {@code portals.yml} across restarts and enter through the same {@link PortalEntry}.
 *
 * <p>A frame is checked when it is lit, when a block of it is broken, blown up, burnt or
 * pushed ({@link PlayerPortalListener}), and every few seconds while a player is near it
 * ({@link PortalCurtains}). The last catches changes no event reports, such as a world
 * edited while the server was down; a block in an unloaded chunk never counts as missing.
 *
 * <p>Server thread only.
 */
public final class PlayerPortals {

    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** What lighting a frame came to: the new portal, or why there is none, and what the player is told. */
    public record Result(Portal portal, String message) {

        public boolean built() {
            return portal != null;
        }
    }

    private final PortalRegistry portals;
    private final Logger logger;
    private PlayerPortalSettings settings;

    public PlayerPortals(PortalRegistry portals, PlayerPortalSettings settings, Logger logger) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public PlayerPortalSettings settings() {
        return settings;
    }

    /** Applies to the next portal lit; portals already built keep their frame block and stay. */
    public void setSettings(PlayerPortalSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Registers a portal for the frame the player just lit, if the rules allow it here.
     *
     * @param opening the frame's opening, from {@link PortalFrame#find}
     */
    public Result build(Player player, String world, ArenaBounds opening) {
        Optional<String> refusal = refusal(player, world, opening);
        if (refusal.isPresent()) {
            return new Result(null, refusal.get());
        }
        Portal portal;
        try {
            portal = portals.createBuilt(world, opening, new Portal.Built(player.getUniqueId(), settings.frameName()));
        } catch (ArenaException e) {
            logger.warning("Could not register the portal " + player.getName() + " lit in " + world + " at "
                    + opening.describe() + ": " + e.getMessage());
            return new Result(null, "This portal could not be opened. Ask an admin to check the server log.");
        }
        logger.info(player.getName() + " (" + player.getUniqueId() + ") built portal " + portal.name() + " in "
                + world + " at " + opening.describe() + ".");
        return new Result(portal, "The portal is open. Walk through it to join the Arena.");
    }

    /** Why the player may not light this opening here, if they may not. */
    public Optional<String> refusal(Player player, String world, ArenaBounds opening) {
        if (!player.hasPermission(PortalEntry.PLAY)) {
            return Optional.of("You do not have permission to build arena portals.");
        }
        if (!settings.allows(world)) {
            return Optional.of("Arena portals cannot be built in this world.");
        }
        int max = settings.maxPerPlayer();
        if (max > 0 && portals.ownedBy(player.getUniqueId()) >= max) {
            return Optional.of("You already have " + max + (max == 1 ? " arena portal" : " arena portals")
                    + ", the most one player may build. Break one to build another.");
        }
        Portal candidate = Portal.built("candidate", world, opening,
                new Portal.Built(player.getUniqueId(), settings.frameName()));
        for (Portal other : portals.all()) {
            if (other.overlaps(candidate)) {
                return Optional.of("This frame is already open.");
            }
        }
        int apart = settings.minDistance();
        for (Portal other : portals.all()) {
            if (other.world().equals(world) && other.distanceTo(candidate) < apart) {
                return Optional.of(String.format(Locale.ROOT, "This is %.0f blocks from another arena portal. Portals"
                        + " must be at least %d blocks apart.", Math.floor(other.distanceTo(candidate)), apart));
            }
        }
        return Optional.empty();
    }

    /**
     * A block is about to break, burn, fade, explode or be pushed away. Removes every
     * player-built portal it is a frame block of; a block next to a frame, or a corner, removes
     * nothing. Looks up only the six blocks beside it.
     *
     * @return the portals removed
     */
    public Set<Portal> frameBlockGone(String world, int x, int y, int z) {
        Set<Portal> gone = new LinkedHashSet<>();
        for (int[] step : NEIGHBOURS) {
            Portal portal = portals.at(world, x + step[0], y + step[1], z + step[2]);
            if (portal != null && portal.playerBuilt() && PortalFrame.isFrame(portal.region(), x, y, z)) {
                gone.add(portal);
            }
        }
        gone.removeIf(portal -> !remove(portal, "a frame block at " + x + ", " + y + ", " + z + " is gone"));
        return gone;
    }

    /**
     * Checks a player-built portal's frame in the world and removes the portal if a frame block
     * is missing. A frame block this server does not know is never judged missing.
     *
     * @return whether the portal is still there
     */
    public boolean verify(Portal portal, World world) {
        Material frame = Material.matchMaterial(portal.built().frame());
        return frame == null || verify(portal, blocks(world, frame));
    }

    boolean verify(Portal portal, PortalFrame.Blocks blocks) {
        if (PortalFrame.intact(blocks, portal.region())) {
            return true;
        }
        remove(portal, "its frame is broken");
        return false;
    }

    /** Removes the portal if the registry still holds it; a failed write is logged and keeps it. */
    private boolean remove(Portal portal, String reason) {
        if (!portal.equals(portals.get(portal.name()).orElse(null))) {
            return false;
        }
        try {
            portals.delete(portal.name());
        } catch (ArenaException e) {
            logger.log(Level.SEVERE, "Could not remove player-built portal " + portal.name() + " after " + reason
                    + ": " + e.getMessage() + ". It keeps working until it is removed.");
            return false;
        }
        logger.info("Removed player-built portal " + portal.name() + " in " + portal.world() + " at "
                + portal.region().describe() + ": " + reason + ".");
        return true;
    }

    /**
     * The world as a frame sees it. Only blocks in loaded chunks are read; the rest are
     * {@link Cell#UNKNOWN}.
     */
    public static PortalFrame.Blocks blocks(World world, Material frame) {
        Objects.requireNonNull(frame, "frame");
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        return (x, y, z) -> {
            if (y < minY || y >= maxY) {
                return Cell.OTHER;
            }
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return Cell.UNKNOWN;
            }
            return cell(world.getType(x, y, z), frame);
        };
    }

    static Cell cell(Material type, Material frame) {
        if (type == frame) {
            return Cell.FRAME;
        }
        return switch (type) {
            case AIR, CAVE_AIR, VOID_AIR, FIRE, SOUL_FIRE -> Cell.EMPTY;
            default -> Cell.OTHER;
        };
    }
}
