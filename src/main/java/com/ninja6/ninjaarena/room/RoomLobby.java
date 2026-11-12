package com.ninja6.ninjaarena.room;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** The lobby as rooms see it: where their players wait between matches. Server thread only. */
public interface RoomLobby {

    boolean isMember(UUID player);

    /** Lobby members in the order they entered. */
    List<UUID> members();

    /**
     * Brings a player into the lobby from outside it, saving their snapshot first. Nothing
     * changes for a player who is already a member.
     *
     * @return empty once they are a member, otherwise why not, as text to show them
     */
    Optional<String> join(Player player);

    /**
     * Puts a player whose snapshot is already saved, back from a match or from spectating, in
     * the lobby. True for a player who is already a member.
     *
     * @return whether they are a member now
     */
    boolean enter(Player player);
}
