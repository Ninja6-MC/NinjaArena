package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Stealth and shuriken-mark aggro steering: the director's {@link TargetWeigher}, set through
 * {@code DirectorService#setWeigher}. Every value comes from {@link StealthSettings}.
 *
 * <ul>
 *   <li><b>Hidden:</b> a crouching player whom no living enemy of the match can see, or who
 *   crouches in low light, has their weight multiplied by the hidden weight.
 *   <li><b>Marked:</b> a player marked by a shuriken has their weight multiplied by the marked
 *   weight. Both apply after the leading-team bias, which stays in the weight.
 *   <li><b>Lose track:</b> an enemy that has not seen the player it chases for the lose-track
 *   time loses track of them. The director checks once a second.
 *   <li><b>Smoke:</b> an enemy inside a smoke bomb cloud drops its target and gets no other
 *   while it stays inside.
 * </ul>
 *
 * <p>Enemy kinds set to ignore stealth skip the hidden cut and the lose-track timer; the
 * mark and smoke still apply to them.
 *
 * <p><b>Cost.</b> Whether a player is hidden is worked out once per server tick and reused
 * for every enemy kind; it looks for a seeing enemy only for a crouching player in light,
 * and stops at the first. Losing track costs one sight check per chasing enemy a second.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class AggroSteering implements TargetWeigher, Listener {

    static final int TICKS_PER_SECOND = 20;

    /** What the steering asks of the world. */
    public interface Senses {

        /** Whether the player is crouching; false for a player who is not online. */
        boolean crouching(UUID player);

        /** The light level where the player stands, 0 to 15. */
        int light(UUID player);

        /** Whether the enemy has line of sight to the player. */
        boolean sees(MatchDirector.Enemy enemy, UUID player);

        /** Whether the enemy is inside a smoke bomb cloud. */
        boolean inSmoke(MatchDirector.Enemy enemy);
    }

    /** The player an enemy chases and the tick it last saw them, or began chasing them. */
    private static final class Sighting {
        final UUID target;
        long seen;

        Sighting(UUID target, long seen) {
            this.target = target;
            this.seen = seen;
        }
    }

    /** One match's sightings, pruned of dead enemies once a tick. */
    private static final class Chases {
        final Map<MatchDirector.Enemy, Sighting> sightings = new IdentityHashMap<>();
        long pruned = Long.MIN_VALUE;
    }

    private final StealthSettings settings;
    private final Senses senses;
    private final Predicate<UUID> marked;
    private final Function<MatchView, List<MatchDirector.Enemy>> enemies;
    private final LongSupplier ticks;
    private final long loseTrackTicks;

    private final Map<UUID, Boolean> hidden = new HashMap<>();
    private long hiddenTick = Long.MIN_VALUE;
    private final Map<MatchView, Chases> chases = new IdentityHashMap<>();

    /**
     * @param marked  whether a shuriken marked the player; {@code NinjaTools#isMarked}
     * @param enemies a match's living enemies; from {@code DirectorService#director}
     * @param ticks   the current server tick, {@code Bukkit#getCurrentTick}
     */
    public AggroSteering(StealthSettings settings, Senses senses, Predicate<UUID> marked,
            Function<MatchView, List<MatchDirector.Enemy>> enemies, LongSupplier ticks) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.senses = Objects.requireNonNull(senses, "senses");
        this.marked = Objects.requireNonNull(marked, "marked");
        this.enemies = Objects.requireNonNull(enemies, "enemies");
        this.ticks = Objects.requireNonNull(ticks, "ticks");
        loseTrackTicks = (long) settings.loseTrackSeconds() * TICKS_PER_SECOND;
    }

    /** The weight for an enemy that does not ignore stealth. */
    @Override
    public double weigh(MatchView match, UUID player, double weight) {
        return steer(match, player, weight, true);
    }

    @Override
    public double weigh(MatchView match, UUID player, double weight, EnemyKind kind) {
        return steer(match, player, weight, !settings.ignoresStealth(kind));
    }

    @Override
    public boolean mayTarget(MatchView match, MatchDirector.Enemy enemy) {
        return !senses.inSmoke(enemy);
    }

    @Override
    public boolean keepsTarget(MatchView match, MatchDirector.Enemy enemy, UUID target) {
        if (settings.ignoresStealth(enemy.kind())) {
            return true;
        }
        long now = ticks.getAsLong();
        Chases chase = chases.computeIfAbsent(match, view -> new Chases());
        if (chase.pruned != now) {
            chase.pruned = now;
            chase.sightings.keySet().removeIf(tracked -> !tracked.isAlive());
        }
        Sighting last = chase.sightings.get(enemy);
        if (last == null || !last.target.equals(target)) {
            chase.sightings.put(enemy, new Sighting(target, now));
            return true;
        }
        if (senses.sees(enemy, target)) {
            last.seen = now;
            return true;
        }
        if (now - last.seen < loseTrackTicks) {
            return true;
        }
        chase.sightings.remove(enemy);
        return false;
    }

    /** Whether the player counts as hidden now; worked out once a tick. */
    public boolean isHidden(MatchView match, UUID player) {
        long now = ticks.getAsLong();
        if (now != hiddenTick) {
            hidden.clear();
            hiddenTick = now;
        }
        Boolean known = hidden.get(player);
        if (known == null) {
            known = hiddenNow(match, player);
            hidden.put(player, known);
        }
        return known;
    }

    /** Forgets the match's chases. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchEnd(MatchEndEvent event) {
        chases.remove(event.match());
        hidden.keySet().removeAll(event.match().players());
    }

    private double steer(MatchView match, UUID player, double weight, boolean stealth) {
        double steered = weight;
        if (stealth && settings.hiddenWeight() < 1 && isHidden(match, player)) {
            steered *= settings.hiddenWeight();
        }
        if (settings.markedWeight() > 1 && marked.test(player)) {
            steered *= settings.markedWeight();
        }
        return steered;
    }

    private boolean hiddenNow(MatchView match, UUID player) {
        if (!senses.crouching(player)) {
            return false;
        }
        if (senses.light(player) <= settings.lowLight()) {
            return true;
        }
        for (MatchDirector.Enemy enemy : enemies.apply(match)) {
            if (enemy.isAlive() && senses.sees(enemy, player)) {
                return false;
            }
        }
        return true;
    }
}
