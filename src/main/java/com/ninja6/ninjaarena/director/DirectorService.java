package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.match.MatchStandings;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs a {@link MatchDirector} for every match, driven by the match events.
 *
 * <p><b>Loop.</b> A match's director gets its own once-a-second task on the server thread
 * when a fight starts (COMBAT), keeps it through OVERRUN, and loses it as soon as the match
 * moves to any other phase. The director itself lives until the match ends; it is dropped at
 * {@link MatchEndEvent}, whatever the reason (completed, forfeit, abandoned when the last
 * player leaves, or stopped on plugin disable), after every other listener has seen the event.
 * {@link #close()} cancels every task on plugin disable. If a director throws, its loop stops
 * for the rest of that fight and the error is logged.
 *
 * <p><b>Players.</b> Damage taken by players alive in a fight counts towards the director's
 * recent damage. A player eliminated or leaving is no longer targeted.
 *
 * <p><b>Extension points.</b> {@link #setGate(SpawnGate)} limits spawning and intensity, and
 * {@link #setWeigher(TargetWeigher)} adjusts targeting weights, for directors created from
 * then on; {@link #director(MatchView)} gives a match's director and its enemies.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class DirectorService implements Listener {

    static final long TICKS_PER_SECOND = 20;

    private static final class Running {
        final MatchDirector director;
        BukkitTask loop;

        Running(MatchDirector director) {
            this.director = director;
        }
    }

    private final Plugin plugin;
    private final IntensitySettings settings;
    private final ThreatBudget budget;
    private final EnemyCosts costs;
    private final MatchStandings standings;
    private final Function<UUID, Optional<MatchView>> matchOf;
    private final Function<MatchView, MatchDirector.Spawner> spawners;
    private final DoubleSupplier random;
    private final Map<MatchView, Running> running = new IdentityHashMap<>();
    private SpawnGate gate = SpawnGate.OPEN;
    private TargetWeigher weigher = TargetWeigher.NONE;
    private boolean closed;

    /**
     * @param standings how teams are scored, for the leading-team bias; {@code TeamScores}
     * @param matchOf   the match a player is in; {@code MatchService#matchOf}
     * @param spawners  makes the spawner for a match; {@link MobSpawner#factory} on a server
     * @param random    a number from 0 (inclusive) to 1 (exclusive) on each call
     */
    public DirectorService(Plugin plugin, IntensitySettings settings, ThreatBudget budget, EnemyCosts costs,
            MatchStandings standings, Function<UUID, Optional<MatchView>> matchOf,
            Function<MatchView, MatchDirector.Spawner> spawners, DoubleSupplier random) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.costs = Objects.requireNonNull(costs, "costs");
        this.standings = Objects.requireNonNull(standings, "standings");
        this.matchOf = Objects.requireNonNull(matchOf, "matchOf");
        this.spawners = Objects.requireNonNull(spawners, "spawners");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Sets the spawn gate for directors created from now on. */
    public void setGate(SpawnGate gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /** Sets the targeting weigher for directors created from now on. */
    public void setWeigher(TargetWeigher weigher) {
        this.weigher = Objects.requireNonNull(weigher, "weigher");
    }

    /** The match's director, from its first fight until the match ends. */
    public Optional<MatchDirector> director(MatchView match) {
        return Optional.ofNullable(running.get(match)).map(entry -> entry.director);
    }

    /** Whether the match's once-a-second loop is running. */
    public boolean isLooping(MatchView match) {
        Running entry = running.get(match);
        return entry != null && entry.loop != null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPhaseChange(MatchPhaseChangeEvent event) {
        if (event.to().isFighting()) {
            startLoop(event.match());
        } else {
            stopLoop(event.match());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchEnd(MatchEndEvent event) {
        stopLoop(event.match());
        running.remove(event.match());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEliminated(PlayerEliminatedEvent event) {
        forget(event.match(), event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeave(PlayerLeaveMatchEvent event) {
        forget(event.match(), event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || running.isEmpty()) {
            return;
        }
        matchOf.apply(player.getUniqueId()).map(running::get)
                .ifPresent(entry -> entry.director.damaged(player.getUniqueId(), event.getFinalDamage()));
    }

    /** Cancels every loop and forgets every director. Called on plugin disable. */
    public void close() {
        closed = true;
        for (Running entry : new ArrayList<>(running.values())) {
            cancel(entry);
        }
        running.clear();
    }

    private void startLoop(MatchView match) {
        if (closed) {
            return;
        }
        Running entry = running.computeIfAbsent(match, view -> new Running(new MatchDirector(view, settings, budget,
                costs, standings, spawners.apply(view), gate, weigher, random)));
        if (entry.loop == null) {
            entry.loop = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(match), TICKS_PER_SECOND,
                    TICKS_PER_SECOND);
        }
    }

    private void stopLoop(MatchView match) {
        Running entry = running.get(match);
        if (entry != null) {
            cancel(entry);
        }
    }

    /** One second of a match's director; stops the loop once the fight is over. */
    void tick(MatchView match) {
        Running entry = running.get(match);
        if (entry == null) {
            return;
        }
        if (!match.phase().isFighting()) {
            cancel(entry);
            return;
        }
        try {
            entry.director.tick();
        } catch (RuntimeException e) {
            cancel(entry);
            plugin.getLogger().log(Level.SEVERE, "The enemy director of arena " + match.arena()
                    + " failed; it spawns nothing more this round.", e);
        }
    }

    private void forget(MatchView match, UUID player) {
        Running entry = running.get(match);
        if (entry != null) {
            entry.director.forget(player);
        }
    }

    private static void cancel(Running entry) {
        if (entry.loop != null) {
            entry.loop.cancel();
            entry.loop = null;
        }
    }
}
