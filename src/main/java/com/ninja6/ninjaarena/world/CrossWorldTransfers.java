package com.ninja6.ninjaarena.world;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.World;

/**
 * Decides whether a match player who is teleported keeps their place in the match.
 *
 * <p>A teleport inside the arena world keeps it, as before the lobby world existed. A
 * teleport into the lobby world, or out of it into the arena world, keeps it only when it is
 * NinjaArena's own ({@code OwnTeleports}) and the player is allowed to cross between the two
 * worlds right now. A teleport to any other world, or to no world, never keeps it.
 *
 * <p>No player is allowed to cross yet. A shop phase or a map preview that moves match
 * players to the lobby world and back switches it on with {@link #allow} for the players it
 * moves; until then every teleport into the lobby world takes the player out of their match.
 */
public final class CrossWorldTransfers {

    private volatile Predicate<UUID> crossing = player -> false;

    /**
     * Sets which match players may currently be moved between the two worlds by
     * NinjaArena's own teleports. Replaces the previous rule.
     */
    public void allow(Predicate<UUID> crossing) {
        this.crossing = Objects.requireNonNull(crossing, "crossing");
    }

    /**
     * @param from        the world the player is leaving, or {@code null} if unknown
     * @param to          the world the player is teleported to, or {@code null} if unknown
     * @param ownTeleport whether NinjaArena started the teleport
     */
    public boolean keepsMatch(UUID player, World from, World to, boolean ownTeleport) {
        if (!PluginWorlds.isPluginWorld(to)) {
            return false;
        }
        if (PluginWorlds.isLobby(to) || PluginWorlds.isLobby(from)) {
            return ownTeleport && crossing.test(player);
        }
        return true;
    }
}
