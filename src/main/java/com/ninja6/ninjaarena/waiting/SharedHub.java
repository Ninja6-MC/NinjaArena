package com.ninja6.ninjaarena.waiting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.UUID;

/**
 * The players waiting in the shared hub, in the order they arrived, and its start countdown.
 * Holds no Bukkit state, so the queue and countdown rules are tested on their own;
 * {@link WaitingRoomService} picks the arena and turns the results into messages.
 *
 * <p><b>Queue.</b> Players are not on teams while they wait: the hub does not know which
 * arena they will play in. When a group is sent, the players who arrived first go first, up
 * to the arena's capacity, and are put on its teams then. The rest stay and keep their place.
 *
 * <p><b>Countdown.</b> It starts as soon as {@code minPlayers} are waiting and runs for
 * {@code countdownSeconds} calls to {@link #tick()}. A departure that takes the count below
 * the minimum cancels it. When it reaches zero, {@link #tick()} reports {@link Phase#READY}
 * and the service {@linkplain #take takes} a group. If enough players are left over, the
 * countdown starts again from the full length for the next free arena. The service sets
 * {@code minPlayers} to the smallest free arena's capacity when that is below
 * {@code min-players}, and back again when that arena is taken.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class SharedHub {

    /** What a call to {@link #tick()} found. */
    public enum Phase {
        /** Fewer than the minimum are waiting; no countdown runs. */
        IDLE,
        /** The countdown is running. */
        COUNTING,
        /** The countdown reached zero: take a group now. */
        READY
    }

    private int minPlayers;
    private int countdownSeconds;
    /** Seconds left while counting; 0 when no countdown runs. */
    private int secondsLeft;
    private final SequencedSet<UUID> queue = new LinkedHashSet<>();

    /**
     * @param minPlayers       players needed to start the countdown; at least 1
     * @param countdownSeconds length of the countdown; at least 1
     */
    public SharedHub(int minPlayers, int countdownSeconds) {
        setLimits(minPlayers, countdownSeconds);
    }

    private void setLimits(int minPlayers, int countdownSeconds) {
        if (minPlayers < 1 || countdownSeconds < 1) {
            throw new IllegalArgumentException("hub settings must be positive");
        }
        this.minPlayers = minPlayers;
        this.countdownSeconds = countdownSeconds;
    }

    /** Keeps a viable countdown's remaining time while changing future countdowns. */
    public void setSettings(int minPlayers, int countdownSeconds) {
        setLimits(minPlayers, countdownSeconds);
        if (queue.size() < minPlayers) {
            secondsLeft = 0;
        } else if (secondsLeft == 0) {
            secondsLeft = countdownSeconds;
        }
    }

    /**
     * Adds a player at the back of the queue.
     *
     * @return whether this arrival reached the minimum and started the countdown
     * @throws IllegalStateException if the player is already waiting here
     */
    public boolean join(UUID player) {
        Objects.requireNonNull(player, "player");
        if (!queue.add(player)) {
            throw new IllegalStateException(player + " is already in the hub");
        }
        if (secondsLeft == 0 && queue.size() >= minPlayers) {
            secondsLeft = countdownSeconds;
            return true;
        }
        return false;
    }

    /**
     * Takes a player out of the queue.
     *
     * @return empty if the player was not here; otherwise whether the departure took the count
     *         below the minimum and stopped the countdown
     */
    public Optional<Boolean> leave(UUID player) {
        if (!queue.remove(player)) {
            return Optional.empty();
        }
        if (secondsLeft > 0 && queue.size() < minPlayers) {
            secondsLeft = 0;
            return Optional.of(true);
        }
        return Optional.of(false);
    }

    /** Advances the countdown by one second. */
    public Phase tick() {
        if (secondsLeft == 0) {
            return Phase.IDLE;
        }
        secondsLeft--;
        return secondsLeft > 0 ? Phase.COUNTING : Phase.READY;
    }

    /**
     * Removes the first {@code count} players, or all of them if fewer are waiting, and
     * starts the countdown again from the full length if enough are left.
     *
     * @return the players taken, in the order they arrived
     */
    public List<UUID> take(int count) {
        List<UUID> taken = new ArrayList<>();
        while (taken.size() < count && !queue.isEmpty()) {
            taken.add(queue.removeFirst());
        }
        secondsLeft = queue.size() >= minPlayers ? countdownSeconds : 0;
        return taken;
    }

    public boolean contains(UUID player) {
        return queue.contains(player);
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int minPlayers() {
        return minPlayers;
    }

    public boolean isCounting() {
        return secondsLeft > 0;
    }

    /** Seconds until the start while the countdown runs, otherwise 0. */
    public int secondsLeft() {
        return secondsLeft;
    }

    /** Every waiting player, first arrival first. */
    public List<UUID> players() {
        return List.copyOf(queue);
    }
}
