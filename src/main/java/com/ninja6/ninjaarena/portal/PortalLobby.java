package com.ninja6.ninjaarena.portal;

import java.util.Optional;
import org.bukkit.entity.Player;

/** Where a play portal leads: the lobby and its rooms, {@code RoomService#fromPortal} on a server. */
@FunctionalInterface
public interface PortalLobby {

    /**
     * Takes a player who walked into a play portal into the lobby, saving their snapshot on
     * the way, and into an open room if there is one for them.
     *
     * @param arena whose open rooms they join, or empty for any room ({@link PortalEntry#roomArena})
     * @return empty once they are in the lobby, otherwise why not, as text to show them
     */
    Optional<String> enter(Player player, Optional<String> arena);
}
