package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.match.MatchStandings;
import com.ninja6.ninjaarena.match.MatchView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * The director of one match: once a second during a fight it works out the target intensity
 * and spends the round's threat budget towards it, spawning enemies through a
 * {@link Spawner} and pointing each at a player drawn by {@link Targeting}.
 *
 * <p><b>Budget.</b> When a round's fight starts, the round budget is
 * {@link ThreatBudget#forRound} with the round multiplier and the player factor for the
 * players alive then. It is the threat allowed in play at intensity 1: each second the
 * director spawns enemies, by their cost, until the threat of its living enemies reaches
 * intensity x budget. An enemy that dies or is removed returns its cost on the next second.
 *
 * <p><b>Targets.</b> Each new enemy, and each living one whose target is no longer alive in
 * the round, gets a player drawn by weight; the team with the most points weighs more, and the
 * {@link TargetWeigher} adjusts each weight for the enemy's kind. Each second the weigher can
 * also clear an enemy's target ({@link TargetWeigher#mayTarget}) or have it lose track of the
 * player it chases ({@link TargetWeigher#keepsTarget}); an enemy that loses track is drawn
 * one of the other players, or none when nobody else is left.
 *
 * <p>Holds no server state: the spawner, the gate and the random source are passed in, and
 * time is the calls to {@link #tick()}. Not thread-safe: call it from the server thread.
 */
public final class MatchDirector {

    /** An enemy the director spawned. */
    public interface Enemy {

        EnemyKind kind();

        /** False once it died or was removed; its cost is then returned to the budget. */
        boolean isAlive();

        /** The player it goes for, or null. */
        UUID target();

        /** Points it at a player, or at nobody for null. */
        void target(UUID player);
    }

    /** Spawns an enemy for the director. */
    @FunctionalInterface
    public interface Spawner {

        /**
         * Spawns one enemy of the kind, aimed at the player (or at nobody for null).
         *
         * @return empty when it could not be placed or spawned; the director tries no more of
         *         that kind this second
         */
        Optional<Enemy> spawn(EnemyKind kind, UUID target);

        /** Why the latest spawn did not happen, for an admin; empty if none failed or it cannot say. */
        default Optional<String> lastRefusal() {
            return Optional.empty();
        }

        /** Explains why the director held back spawning before trying a placement. */
        default void blocked(Blockage reason, double targetThreat, double inPlay) {
        }
    }

    /** A limit that holds spawning back before placement. */
    public enum Blockage {
        BUDGET, GATE
    }

    /** The living enemies of one kind, and the threat they hold. */
    public record InPlay(EnemyKind kind, int count, int threat) {
    }

    private record Tracked(Enemy enemy, int cost) {
    }

    private final MatchView match;
    private final IntensitySettings settings;
    private final ThreatBudget budget;
    private final EnemyCosts costs;
    private final MatchStandings standings;
    private final Spawner spawner;
    private final SpawnGate gate;
    private final TargetWeigher weigher;
    private final DoubleSupplier random;
    private final DamageWindow damage;
    private final List<Tracked> enemies = new ArrayList<>();

    private int round;
    private double roundBudget;
    private double intensity;

    /**
     * @param standings how teams are scored, for the leading-team bias
     * @param random    a number from 0 (inclusive) to 1 (exclusive) on each call
     */
    public MatchDirector(MatchView match, IntensitySettings settings, ThreatBudget budget, EnemyCosts costs,
            MatchStandings standings, Spawner spawner, SpawnGate gate, TargetWeigher weigher, DoubleSupplier random) {
        this.match = Objects.requireNonNull(match, "match");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.costs = Objects.requireNonNull(costs, "costs");
        this.standings = Objects.requireNonNull(standings, "standings");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.weigher = Objects.requireNonNull(weigher, "weigher");
        this.random = Objects.requireNonNull(random, "random");
        damage = new DamageWindow(settings.damageWindowSeconds());
    }

    public MatchView match() {
        return match;
    }

    /**
     * One second of the director. Does nothing outside COMBAT and OVERRUN. The first call in a
     * new round sets the round budget and forgets earlier damage.
     */
    public void tick() {
        if (!match.phase().isFighting()) {
            intensity = 0;
            return;
        }
        if (match.round() != round) {
            startRound();
        }
        prune();
        List<UUID> alive = match.alivePlayers();
        double damagePerPlayer = alive.isEmpty() ? 0 : damage.total() / alive.size();
        damage.advance();
        int combatSeconds = Math.max(0, match.roundElapsedSeconds() - match.settings().prepSeconds());
        double target = Intensity.target(settings, new Intensity.Inputs(match.phase(), round, combatSeconds,
                match.settings().roundSeconds(), match.aliveTeams().size(), match.startingTeams().size(),
                damagePerPlayer));
        double gated = gate.intensity(match, target);
        intensity = Double.isFinite(gated) ? Math.clamp(gated, 0, 1) : 0;
        if (alive.isEmpty()) {
            return;
        }
        Weights weights = new Weights();
        retarget(weights);
        spend(intensity * roundBudget, weights);
    }

    /** Damage taken by a player of the match; counted while they are alive in the round. */
    public void damaged(UUID player, double amount) {
        if (match.isAlive(player)) {
            damage.add(amount);
        }
    }

    /** Clears the target of every enemy going for the player, who is out of the round or the match. */
    public void forget(UUID player) {
        for (Tracked tracked : enemies) {
            if (player.equals(tracked.enemy().target())) {
                tracked.enemy().target(null);
            }
        }
    }

    /** The intensity of the last second, from 0 to 1. */
    public double intensity() {
        return intensity;
    }

    /** The current round's budget; 0 before the first fight. */
    public double roundBudget() {
        return roundBudget;
    }

    /** The cost of the living enemies as of the last second. */
    public double threatInPlay() {
        double total = 0;
        for (Tracked tracked : enemies) {
            total += tracked.cost();
        }
        return total;
    }

    /**
     * The enemies spawned and still tracked: alive as of the last second. Enemies are kept
     * across rounds until they die or are removed.
     */
    public List<Enemy> enemies() {
        return enemies.stream().map(Tracked::enemy).toList();
    }

    /** The tracked enemies by kind, in kind order, with their threat; kinds with none are left out. */
    public List<InPlay> inPlay() {
        Map<EnemyKind, int[]> byKind = new EnumMap<>(EnemyKind.class);
        for (Tracked tracked : enemies) {
            int[] sum = byKind.computeIfAbsent(tracked.enemy().kind(), kind -> new int[2]);
            sum[0]++;
            sum[1] += tracked.cost();
        }
        List<InPlay> out = new ArrayList<>();
        byKind.forEach((kind, sum) -> out.add(new InPlay(kind, sum[0], sum[1])));
        return out;
    }

    /** Why the latest spawn did not happen, as the spawner tells it. */
    public Optional<String> lastRefusal() {
        return spawner.lastRefusal();
    }

    private void startRound() {
        round = match.round();
        roundBudget = budget.forRound(settings.roundMultiplier(round), settings.playerFactor(match.alivePlayers().size()));
        damage.clear();
    }

    private void prune() {
        enemies.removeIf(tracked -> !tracked.enemy().isAlive());
    }

    private void retarget(Weights weights) {
        for (Tracked tracked : enemies) {
            Enemy enemy = tracked.enemy();
            UUID target = enemy.target();
            if (!weigher.mayTarget(match, enemy)) {
                if (target != null) {
                    enemy.target(null);
                }
            } else if (target == null || !match.isAlive(target)) {
                enemy.target(Targeting.pick(weights.of(enemy.kind()), random));
            } else if (!weigher.keepsTarget(match, enemy, target)) {
                Map<UUID, Double> others = new LinkedHashMap<>(weights.of(enemy.kind()));
                others.remove(target);
                enemy.target(Targeting.pick(others, random));
            }
        }
    }

    private void spend(double threat, Weights weights) {
        EnumSet<EnemyKind> usable = EnumSet.allOf(EnemyKind.class);
        double inPlay = threatInPlay();
        boolean placed = false;
        boolean refused = false;
        while (true) {
            double gap = threat - inPlay;
            List<EnemyKind> affordable = usable.stream().filter(kind -> kind.cost(costs) <= gap).toList();
            if (affordable.isEmpty()) {
                // A successful fill is not a refusal; placement failures already explain themselves.
                if (!placed && !refused) {
                    spawner.blocked(Blockage.BUDGET, threat, inPlay);
                }
                return;
            }
            if (!gate.maySpawn(match)) {
                if (!refused) {
                    spawner.blocked(Blockage.GATE, threat, inPlay);
                }
                return;
            }
            EnemyKind kind = affordable.get(index(affordable.size()));
            Optional<Enemy> spawned = spawner.spawn(kind, Targeting.pick(weights.of(kind), random));
            if (spawned.isEmpty()) {
                refused = true;
                usable.remove(kind);
                continue;
            }
            int cost = kind.cost(costs);
            enemies.add(new Tracked(spawned.get(), cost));
            placed = true;
            inPlay += cost;
            gate.spawned(match, kind);
        }
    }

    private int index(int size) {
        double roll = Math.clamp(random.getAsDouble(), 0, Math.nextDown(1.0));
        return Math.min(size - 1, (int) (roll * size));
    }

    /** One second's targeting weights, worked out for each enemy kind when first needed. */
    private final class Weights {

        private final Map<EnemyKind, Map<UUID, Double>> byKind = new EnumMap<>(EnemyKind.class);

        Map<UUID, Double> of(EnemyKind kind) {
            return byKind.computeIfAbsent(kind,
                    key -> Targeting.weights(match, standings, settings.leaderBias(), weigher, key));
        }
    }
}
