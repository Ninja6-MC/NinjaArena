package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchStandings;
import com.ninja6.ninjaarena.match.MatchView;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchDirectorTest {

    private static final double DELTA = 1e-9;
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);

    static final class FakeEnemy implements MatchDirector.Enemy {
        final EnemyKind kind;
        boolean alive = true;
        UUID target;

        FakeEnemy(EnemyKind kind, UUID target) {
            this.kind = kind;
            this.target = target;
        }

        @Override
        public EnemyKind kind() {
            return kind;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public UUID target() {
            return target;
        }

        @Override
        public void target(UUID player) {
            target = player;
        }
    }

    record Attempt(EnemyKind kind, UUID target) {
    }

    private final Map<Integer, Integer> points = new HashMap<>();
    private final MatchStandings standings = (match, team) -> points.getOrDefault(team, 0);
    private final List<Attempt> attempts = new ArrayList<>();
    private final List<FakeEnemy> spawned = new ArrayList<>();
    private final Set<EnemyKind> refused = EnumSet.noneOf(EnemyKind.class);
    private final MatchDirector.Spawner spawner = (kind, target) -> {
        attempts.add(new Attempt(kind, target));
        if (refused.contains(kind)) {
            return Optional.empty();
        }
        FakeEnemy enemy = new FakeEnemy(kind, target);
        spawned.add(enemy);
        return Optional.of(enemy);
    };
    private double roll;
    private FakeMatch match;

    @BeforeEach
    void setUp() {
        match = new FakeMatch(List.of(List.of(A, C), List.of(B, D))).combat(0);
    }

    private MatchDirector director() {
        return director(SpawnGate.OPEN);
    }

    private MatchDirector director(SpawnGate gate) {
        return new MatchDirector(match, IntensitySettings.DEFAULTS, new ThreatBudget(20), EnemyCosts.DEFAULTS,
                standings, spawner, gate, TargetWeigher.NONE, () -> roll);
    }

    @Test
    void spendsTheRoundBudgetUpToTheTargetIntensity() {
        var director = director();
        director.tick();
        // Round 1, four players: budget 20 x 1 x 1. The fight opens at the relax level, 0.15.
        assertEquals(20, director.roundBudget(), DELTA);
        assertEquals(0.15, director.intensity(), DELTA);
        assertEquals(3, spawned.size());
        assertEquals(3, director.threatInPlay(), DELTA);
        assertTrue(spawned.stream().allMatch(enemy -> enemy.kind == EnemyKind.ZOMBIE && A.equals(enemy.target)));

        match.combat(40);
        director.tick();
        double target = (0.6 + 0.2 * 40 / 240) * 20;
        assertEquals(12, director.threatInPlay(), DELTA);
        assertTrue(director.threatInPlay() <= target && target - director.threatInPlay() < 1);
        assertEquals(12, director.enemies().size());

        director.tick();
        assertEquals(12, spawned.size(), "nothing more while the target is met");
    }

    @Test
    void mixesEnemyKindsByCostWithoutOverspending() {
        roll = 0.5;
        var director = director();
        director.tick();
        // Three points: a skeleton (2) is drawn first, then only a zombie (1) still fits.
        assertEquals(List.of(EnemyKind.SKELETON, EnemyKind.ZOMBIE), spawned.stream().map(enemy -> enemy.kind).toList());
        assertEquals(3, director.threatInPlay(), DELTA);
    }

    @Test
    void deadOrRemovedEnemiesReturnTheirCost() {
        var director = director();
        director.tick();
        spawned.get(0).alive = false;
        spawned.get(2).alive = false;
        director.tick();
        assertEquals(5, spawned.size());
        assertEquals(3, director.enemies().size());
        assertEquals(3, director.threatInPlay(), DELTA);
    }

    @Test
    void budgetEscalatesEachRoundAndFollowsThePlayersAliveWhenTheFightStarts() {
        var director = director();
        director.tick();
        assertEquals(20, director.roundBudget(), DELTA);

        spawned.forEach(enemy -> enemy.alive = false);
        match.nextRound();
        director.tick();
        assertEquals(25, director.roundBudget(), DELTA);
        assertEquals(0.15 + 0.1 * 0.25, director.intensity(), DELTA);

        match.nextRound();
        match.alive.remove(A);
        match.alive.remove(B);
        director.tick();
        assertEquals(20 * 1.5 * 0.5, director.roundBudget(), DELTA);
        // A later death does not change the budget of the round.
        match.alive.remove(C);
        director.tick();
        assertEquals(15, director.roundBudget(), DELTA);
    }

    @Test
    void enemiesLeftFromAnEarlierRoundCountAgainstTheNewBudget() {
        var director = director();
        director.tick();
        match.nextRound();
        director.tick();
        // 25 x 0.175 = 4.375: three zombies already alive, one more fits.
        assertEquals(4, spawned.size());
        assertEquals(4, director.threatInPlay(), DELTA);
    }

    @Test
    void spawnsNothingOutsideAFight() {
        var director = director();
        for (MatchPhase phase : new MatchPhase[] {MatchPhase.WAITING, MatchPhase.PREP, MatchPhase.ROUND_END,
                MatchPhase.MATCH_END}) {
            match.phase = phase;
            director.tick();
        }
        assertEquals(List.of(), attempts);
        assertEquals(0, director.roundBudget());
        assertEquals(0, director.intensity());
    }

    @Test
    void overrunSpendsTheWholeBudget() {
        match.phase = MatchPhase.OVERRUN;
        match.elapsed = 300;
        var director = director();
        director.tick();
        assertEquals(1, director.intensity());
        assertEquals(20, director.threatInPlay(), DELTA);
    }

    @Test
    void spawnsNothingWithNobodyAlive() {
        match.alive.clear();
        var director = director();
        director.tick();
        assertEquals(List.of(), attempts);
    }

    @Test
    void aKindThatCannotBePlacedIsNotTriedAgainThatSecond() {
        refused.add(EnemyKind.ZOMBIE);
        var director = director();
        director.tick();
        assertEquals(List.of(EnemyKind.ZOMBIE, EnemyKind.SKELETON), attempts.stream().map(Attempt::kind).toList());
        assertEquals(2, director.threatInPlay(), DELTA);

        attempts.clear();
        director.tick();
        assertEquals(List.of(EnemyKind.ZOMBIE), attempts.stream().map(Attempt::kind).toList());

        refused.addAll(EnumSet.allOf(EnemyKind.class));
        attempts.clear();
        match.combat(45);
        director.tick();
        assertEquals(3, attempts.size(), "each kind is tried once, then the second ends");
    }

    @Test
    void theGateLimitsSpawnsAndIntensity() {
        List<EnemyKind> counted = new ArrayList<>();
        SpawnGate cap = new SpawnGate() {
            @Override
            public boolean maySpawn(MatchView view) {
                return counted.size() < 2;
            }

            @Override
            public void spawned(MatchView view, EnemyKind kind) {
                counted.add(kind);
            }
        };
        match.combat(45);
        var director = director(cap);
        director.tick();
        assertEquals(2, spawned.size());
        assertEquals(List.of(EnemyKind.ZOMBIE, EnemyKind.ZOMBIE), counted);

        spawned.clear();
        SpawnGate halve = new SpawnGate() {
            @Override
            public double intensity(MatchView view, double target) {
                return target / 10;
            }
        };
        director = director(halve);
        director.tick();
        assertEquals(0.06375, director.intensity(), DELTA);
        assertEquals(1, spawned.size());

        SpawnGate broken = new SpawnGate() {
            @Override
            public double intensity(MatchView view, double target) {
                return Double.NaN;
            }
        };
        director = director(broken);
        director.tick();
        assertEquals(0, director.intensity());
    }

    @Test
    void enemiesGoForTheLeadingTeam() {
        roll = 0.4;
        var level = director();
        level.tick();
        // Level teams: weights 1, 1, 1, 1 and a roll of 0.4 lands on Carol.
        assertTrue(spawned.stream().allMatch(enemy -> C.equals(enemy.target)));

        spawned.clear();
        points.put(2, 5);
        var leading = director();
        leading.tick();
        // Team 2 leads: weights 1, 1, 2, 2 and the same roll lands on Bob.
        assertTrue(!spawned.isEmpty() && spawned.stream().allMatch(enemy -> B.equals(enemy.target)));
    }

    @Test
    void enemiesWhoseTargetIsOutAreRetargeted() {
        var director = director();
        director.tick();
        assertTrue(spawned.stream().allMatch(enemy -> A.equals(enemy.target)));

        match.alive.remove(A);
        director.tick();
        assertTrue(spawned.stream().allMatch(enemy -> C.equals(enemy.target)));

        spawned.get(0).target = null;
        director.tick();
        assertEquals(C, spawned.get(0).target);
    }

    @Test
    void forgetClearsTheTargetsOnAPlayerAtOnce() {
        var director = director();
        director.tick();
        spawned.get(1).target = B;
        director.forget(A);
        assertNull(spawned.get(0).target);
        assertNull(spawned.get(2).target);
        assertEquals(B, spawned.get(1).target);
    }

    @Test
    void recentDamageEasesThePressureUntilItAgesOut() {
        match.combat(45);
        var director = director();
        director.tick();
        double calm = 0.6 + 0.2 * 45 / 240;
        assertEquals(calm, director.intensity(), DELTA);

        // 80 damage over four players is full stress: the intensity is cut by half.
        director.damaged(A, 50);
        director.damaged(B, 30);
        director.tick();
        assertEquals(calm / 2, director.intensity(), DELTA);
        for (int second = 0; second < 9; second++) {
            director.tick();
        }
        assertEquals(calm / 2, director.intensity(), DELTA);
        director.tick();
        assertEquals(calm, director.intensity(), DELTA);
    }

    @Test
    void damageOfPlayersOutOfTheRoundIsIgnoredAndANewRoundStartsCalm() {
        match.combat(45);
        var director = director();
        director.tick();
        double calm = director.intensity();
        match.alive.remove(D);
        director.damaged(D, 500);
        match.alive.add(D);
        director.tick();
        assertEquals(calm, director.intensity(), DELTA);

        director.damaged(A, 500);
        match.nextRound();
        match.combat(45);
        director.tick();
        assertEquals(0.7 + 0.2 * 45 / 240, director.intensity(), DELTA);
    }
}
