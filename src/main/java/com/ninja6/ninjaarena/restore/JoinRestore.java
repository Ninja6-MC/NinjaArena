package com.ninja6.ninjaarena.restore;

import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Drives the restore of a pending arena snapshot after a join: the first attempt one tick
 * after the join, bounded retries when it did not complete, and one correction when another
 * plugin moves the player right after it did.
 *
 * <p>A restore that another plugin cancels, for example a login plugin holding the player at
 * a spawn until they authenticate, leaves the snapshot pending. It is attempted again after
 * each of {@link #RETRY_DELAYS} ticks, so there are at most {@code RETRY_DELAYS.length + 1}
 * attempts. Every attempt writes the snapshot over the player rather than adding to them, so
 * a retry cannot duplicate items. When the last attempt fails the player is told, and the
 * snapshot stays for their next join.
 *
 * <p>Ordering with a plugin that places the player on join (a plot plugin): the restore owns
 * the final position. A placement that lands before the restore is overwritten by it; one
 * that lands within {@link #SETTLE_TICKS} after it is undone once by moving the player back
 * to the restored spot. The other plugin's own state, such as an assigned plot or respawn
 * point, is not touched. Server thread only.
 */
public final class JoinRestore {

    /** Ticks before the first attempt, once the join has finished. */
    static final long FIRST_DELAY = 1;
    /** Ticks before each retry, in order: about 66 seconds in total. */
    static final long[] RETRY_DELAYS = {20, 100, 200, 400, 600};
    /** Ticks after a restore during which a competing teleport is corrected. */
    static final long SETTLE_TICKS = 100;

    /** Runs a task after a number of ticks. */
    @FunctionalInterface
    public interface Delayer {
        void later(long ticks, Runnable task);
    }

    private record Settle(Location landing, int generation) { }

    private final RestoreService restores;
    private final Delayer delayer;
    private final Function<UUID, Player> online;
    private final Map<UUID, Integer> generations = new HashMap<>();
    private final Map<UUID, Settle> settling = new HashMap<>();

    public JoinRestore(RestoreService restores, Delayer delayer, Function<UUID, Player> online) {
        this.restores = Objects.requireNonNull(restores, "restores");
        this.delayer = Objects.requireNonNull(delayer, "delayer");
        this.online = Objects.requireNonNull(online, "online");
    }

    /** A player joined; starts a restore chain if they have a pending snapshot. */
    public void onJoin(UUID playerId) {
        if (!restores.shouldRestoreOnJoin(playerId)) {
            return;
        }
        int generation = next(playerId);
        delayer.later(FIRST_DELAY, () -> attempt(playerId, generation, 0));
    }

    /** A player left; drops anything scheduled for their connection. */
    public void onQuit(UUID playerId) {
        next(playerId);
    }

    private int next(UUID playerId) {
        settling.remove(playerId);
        return generations.merge(playerId, 1, Integer::sum);
    }

    private boolean current(UUID playerId, int generation) {
        return generations.getOrDefault(playerId, 0) == generation;
    }

    private void attempt(UUID playerId, int generation, int attempt) {
        Player player = online.apply(playerId);
        if (!current(playerId, generation) || player == null || !player.isOnline()) {
            return;
        }
        RestoreResult result = restores.restore(player);
        switch (result) {
            case RESTORED -> settle(player, generation);
            case FAILED -> {
                if (attempt < RETRY_DELAYS.length) {
                    delayer.later(RETRY_DELAYS[attempt], () -> attempt(playerId, generation, attempt + 1));
                } else {
                    player.sendMessage(Component.text("Your inventory and location could not be restored yet."
                            + " Rejoin the server, or ask staff for help.", NamedTextColor.RED));
                }
            }
            // Nothing left to do, the respawn listener takes over, or the next join retries.
            default -> { }
        }
    }

    private void settle(Player player, int generation) {
        UUID playerId = player.getUniqueId();
        settling.put(playerId, new Settle(player.getLocation().clone(), generation));
        delayer.later(SETTLE_TICKS, () -> {
            Settle settle = settling.get(playerId);
            if (settle != null && settle.generation() == generation) {
                settling.remove(playerId);
            }
        });
    }

    /**
     * Another plugin moved a player who was just restored. Moves them back to the restored
     * spot once, a tick later so the other plugin's teleport has finished.
     *
     * @param destination where the other plugin is sending the player
     * @param cause       why the teleport happened
     */
    public void onForeignTeleport(Player player, Location destination, TeleportCause cause) {
        UUID playerId = player.getUniqueId();
        Settle settle = settling.get(playerId);
        if (settle == null || !current(playerId, settle.generation())
                || cause != TeleportCause.PLUGIN && cause != TeleportCause.UNKNOWN
                || sameSpot(destination, settle.landing())) {
            return;
        }
        // One correction per restore; a plugin that insists is left to win.
        settling.remove(playerId);
        delayer.later(1, () -> {
            Player current = online.apply(playerId);
            if (current != null && current.isOnline() && current(playerId, settle.generation())) {
                current.teleport(settle.landing(), TeleportCause.PLUGIN);
            }
        });
    }

    private static boolean sameSpot(Location a, Location b) {
        return a != null && b != null && Objects.equals(a.getWorld(), b.getWorld())
                && square(a.getX() - b.getX()) + square(a.getY() - b.getY()) + square(a.getZ() - b.getZ()) < 1;
    }

    private static double square(double value) {
        return value * value;
    }
}
