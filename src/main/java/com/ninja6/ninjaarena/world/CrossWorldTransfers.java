package com.ninja6.ninjaarena.world;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.bukkit.World;

/**
 * Decides whether a match player who is teleported keeps their place in the match.
 *
 * <p>A teleport inside the arena world keeps it, as before the lobby world existed. A
 * teleport into the lobby world, or out of it into the arena world, keeps it only when it is
 * NinjaArena's own ({@code OwnTeleports}) and a registered rule accepts the player. A
 * teleport to any other world, or to no world, never keeps it.
 *
 * <p>No rule is registered yet, so every teleport into the lobby world takes the player out
 * of their match. A feature that moves match players to the lobby world and back, such as a
 * shop phase or a map preview, registers its own rule with {@link #allow} for the players it
 * moves and runs the returned handle when it ends. Rules do not replace each other: a
 * player crosses while any registered rule accepts them.
 */
public final class CrossWorldTransfers {

    private final Set<Rule> rules = new CopyOnWriteArraySet<>();

    /** Identity-compared, so the same predicate can be registered twice and released apart. */
    private record Rule(Predicate<UUID> crossing) {

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /**
     * Lets the players {@code crossing} accepts be moved between the two worlds by
     * NinjaArena's own teleports, until the returned handle is run. Running it again does
     * nothing.
     */
    public Runnable allow(Predicate<UUID> crossing) {
        Rule rule = new Rule(Objects.requireNonNull(crossing, "crossing"));
        rules.add(rule);
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                rules.remove(rule);
            }
        };
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
            return ownTeleport && rules.stream().anyMatch(rule -> rule.crossing().test(player));
        }
        return true;
    }
}
