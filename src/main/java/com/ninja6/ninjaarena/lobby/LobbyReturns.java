package com.ninja6.ninjaarena.lobby;

import com.ninja6.ninjaarena.restore.JoinRestore;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Remembers who quit from the lobby, a room, a match or spectating, so that their next join
 * puts them back in the lobby with their snapshot kept rather than restoring them home. It
 * is kept in memory: after a restart everyone with a snapshot is restored home as before.
 * Players in a waiting room or the shared hub are restored home on their next join, as before.
 *
 * <p>The quit is seen first, before the match, the lobby and spectating let the player go.
 * Server thread only.
 */
public final class LobbyReturns implements Listener, JoinRestore.Rejoin {

    private final Predicate<UUID> inLobbyFlow;
    private final Predicate<Player> enter;
    private final Set<UUID> returning = new HashSet<>();

    /**
     * @param inLobbyFlow whether a player is in the lobby, a room or a match, or spectating
     * @param enter       puts a player whose snapshot is saved in the lobby; false if it could not
     */
    public LobbyReturns(Predicate<UUID> inLobbyFlow, Predicate<Player> enter) {
        this.inLobbyFlow = Objects.requireNonNull(inLobbyFlow, "inLobbyFlow");
        this.enter = Objects.requireNonNull(enter, "enter");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        quit(event.getPlayer().getUniqueId());
    }

    /** A player is leaving the server. */
    public void quit(UUID player) {
        if (inLobbyFlow.test(player)) {
            returning.add(player);
        } else {
            returning.remove(player);
        }
    }

    @Override
    public boolean claim(UUID player) {
        return returning.remove(player);
    }

    @Override
    public boolean enter(Player player) {
        return enter.test(player);
    }
}
