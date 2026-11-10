package com.ninja6.ninjaarena.restore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Marks the teleports NinjaArena starts itself (restore, admission, waiting room, match,
 * portal), so {@link JoinRestore} can tell them from another plugin's. The teleport event
 * fires inside the call, so the mark only has to cover the call. Server thread only.
 */
public final class OwnTeleports {

    private static final Map<UUID, Integer> IN_FLIGHT = new HashMap<>();

    private OwnTeleports() { }

    /** Teleports the player and marks the teleport as NinjaArena's own. */
    public static boolean teleport(Player player, Location to, TeleportCause cause) {
        return during(player.getUniqueId(), () -> player.teleport(to, cause));
    }

    /** Runs a teleport call that is NinjaArena's own. */
    public static boolean during(UUID playerId, BooleanSupplier teleport) {
        begin(playerId);
        try {
            return teleport.getAsBoolean();
        } finally {
            end(playerId);
        }
    }

    /** Starts a mark that outlives the call, for an asynchronous teleport; pair with {@link #end}. */
    public static void begin(UUID playerId) {
        IN_FLIGHT.merge(playerId, 1, Integer::sum);
    }

    /** Ends a mark started with {@link #begin}. */
    public static void end(UUID playerId) {
        IN_FLIGHT.computeIfPresent(playerId, (id, n) -> n > 1 ? n - 1 : null);
    }

    /** Whether NinjaArena is teleporting this player right now. */
    public static boolean isActive(UUID playerId) {
        return IN_FLIGHT.containsKey(playerId);
    }
}
