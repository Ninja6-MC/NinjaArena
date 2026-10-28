package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.director.MatchDirectorTest.FakeEnemy;
import com.ninja6.ninjaarena.match.MatchResult;
import com.ninja6.ninjaarena.match.MatchStandings;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AggroSteeringTest {

    private static final double DELTA = 1e-9;
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);

    /** The world as the test sets it, counting the sight checks. */
    private final class FakeSenses implements AggroSteering.Senses {
        final Set<UUID> crouching = new HashSet<>();
        final Map<UUID, Integer> light = new HashMap<>();
        /** Pairs of enemy and player with line of sight. */
        final Map<MatchDirector.Enemy, Set<UUID>> sight = new HashMap<>();
        final Set<MatchDirector.Enemy> smoke = new HashSet<>();
        int crouchChecks;
        int sightChecks;

        @Override
        public boolean crouching(UUID player) {
            crouchChecks++;
            return crouching.contains(player);
        }

        @Override
        public int light(UUID player) {
            return light.getOrDefault(player, 15);
        }

        @Override
        public boolean sees(MatchDirector.Enemy enemy, UUID player) {
            sightChecks++;
            return sight.getOrDefault(enemy, Set.of()).contains(player);
        }

        @Override
        public boolean inSmoke(MatchDirector.Enemy enemy) {
            return smoke.contains(enemy);
        }

        void see(MatchDirector.Enemy enemy, UUID player) {
            sight.computeIfAbsent(enemy, key -> new HashSet<>()).add(player);
        }

        void blind(MatchDirector.Enemy enemy) {
            sight.remove(enemy);
        }
    }

    private final FakeMatch match = new FakeMatch(List.of(List.of(A, C), List.of(B))).combat(0);
    private final FakeSenses senses = new FakeSenses();
    private final Set<UUID> marked = new HashSet<>();
    private final List<MatchDirector.Enemy> enemies = new ArrayList<>();
    private long tick = 100;

    private AggroSteering steering(StealthSettings settings) {
        return new AggroSteering(settings, senses, marked::contains, view -> enemies, () -> tick);
    }

    private AggroSteering steering() {
        return steering(StealthSettings.DEFAULTS);
    }

    private FakeEnemy enemy(EnemyKind kind, UUID target) {
        FakeEnemy enemy = new FakeEnemy(kind, target);
        enemies.add(enemy);
        return enemy;
    }

    private static StealthSettings ignoring(EnemyKind kind) {
        StealthSettings d = StealthSettings.DEFAULTS;
        return new StealthSettings(d.hiddenWeight(), d.lowLight(), d.loseTrackSeconds(), d.markedWeight(),
                kind == EnemyKind.ZOMBIE, kind == EnemyKind.SKELETON, kind == EnemyKind.SPIDER, false);
    }

    @Test
    void aStandingPlayerKeepsTheirWeight() {
        enemy(EnemyKind.ZOMBIE, null);
        assertEquals(2, steering().weigh(match, A, 2, EnemyKind.ZOMBIE), DELTA);
    }

    @Test
    void aCrouchingPlayerNoEnemySeesIsCutToAQuarter() {
        FakeEnemy zombie = enemy(EnemyKind.ZOMBIE, null);
        senses.crouching.add(A);
        assertEquals(0.25, steering().weigh(match, A, 1, EnemyKind.ZOMBIE), DELTA);

        tick++;
        senses.see(zombie, A);
        assertEquals(1, steering().weigh(match, A, 1, EnemyKind.ZOMBIE), DELTA, "seen by one enemy");
    }

    @Test
    void onlyLivingEnemiesGiveAPlayerAway() {
        FakeEnemy zombie = enemy(EnemyKind.ZOMBIE, null);
        senses.crouching.add(A);
        senses.see(zombie, A);
        zombie.alive = false;
        assertEquals(0.25, steering().weigh(match, A, 1, EnemyKind.ZOMBIE), DELTA);
    }

    @Test
    void lowLightHidesACrouchingPlayerInPlainSight() {
        FakeEnemy zombie = enemy(EnemyKind.ZOMBIE, null);
        senses.crouching.add(A);
        senses.see(zombie, A);
        senses.light.put(A, 7);
        AggroSteering steering = steering();
        assertEquals(0.25, steering.weigh(match, A, 1, EnemyKind.ZOMBIE), DELTA);
        assertEquals(0, senses.sightChecks, "low light needs no sight check");

        tick++;
        senses.light.put(A, 8);
        assertEquals(1, steering.weigh(match, A, 1, EnemyKind.ZOMBIE), DELTA);

        tick++;
        senses.crouching.remove(A);
        senses.light.put(A, 0);
        assertEquals(1, steering.weigh(match, A, 1, EnemyKind.ZOMBIE), DELTA, "standing in the dark");
    }

    @Test
    void lowLightCanBeTurnedOff() {
        FakeEnemy zombie = enemy(EnemyKind.ZOMBIE, null);
        senses.crouching.add(A);
        senses.see(zombie, A);
        senses.light.put(A, 0);
        StealthSettings d = StealthSettings.DEFAULTS;
        var steering = steering(new StealthSettings(d.hiddenWeight(), -1, 3, 2, false, false, false, false));
        assertEquals(1, steering.weigh(match, A, 1, EnemyKind.ZOMBIE), DELTA);
    }

    @Test
    void aMarkedPlayerWeighsMoreAndTheLeaderBiasStays() {
        marked.add(B);
        AggroSteering steering = steering();
        assertEquals(2, steering.weigh(match, B, 1, EnemyKind.SPIDER), DELTA);
        assertEquals(4, steering.weigh(match, B, 2, EnemyKind.SPIDER), DELTA, "leader bias 2, marked");
        senses.crouching.add(B);
        tick++;
        assertEquals(1, steering.weigh(match, B, 2, EnemyKind.SPIDER), DELTA, "leader, marked and hidden");
    }

    @Test
    void weightsThroughTargetingKeepTheLeaderBiasOnTop() {
        Map<Integer, Integer> points = Map.of(2, 5);
        MatchStandings standings = (view, team) -> points.getOrDefault(team, 0);
        senses.crouching.add(A);
        marked.add(B);
        assertEquals(Map.of(A, 0.25, C, 1.0, B, 5.0),
                Targeting.weights(match, standings, 2.5, steering(), EnemyKind.ZOMBIE));
    }

    @Test
    void kindsThatIgnoreStealthSkipTheCutButNotTheMark() {
        senses.crouching.add(A);
        marked.add(A);
        AggroSteering steering = steering(ignoring(EnemyKind.SKELETON));
        assertEquals(2, steering.weigh(match, A, 1, EnemyKind.SKELETON), DELTA);
        assertEquals(0.5, steering.weigh(match, A, 1, EnemyKind.ZOMBIE), DELTA);
        assertEquals(0.5, steering.weigh(match, A, 1), DELTA, "no kind given counts stealth");
    }

    @Test
    void hiddenIsWorkedOutOncePerTick() {
        enemy(EnemyKind.ZOMBIE, null);
        enemy(EnemyKind.SPIDER, null);
        senses.crouching.add(A);
        AggroSteering steering = steering();
        for (EnemyKind kind : EnemyKind.values()) {
            steering.weigh(match, A, 1, kind);
        }
        assertEquals(1, senses.crouchChecks);
        assertEquals(2, senses.sightChecks);

        tick++;
        steering.weigh(match, A, 1, EnemyKind.ZOMBIE);
        assertEquals(2, senses.crouchChecks);
    }

    @Test
    void theFirstSeeingEnemyEndsTheSearch() {
        FakeEnemy first = enemy(EnemyKind.ZOMBIE, null);
        enemy(EnemyKind.ZOMBIE, null);
        enemy(EnemyKind.ZOMBIE, null);
        senses.crouching.add(A);
        senses.see(first, A);
        assertFalse(steering().isHidden(match, A));
        assertEquals(1, senses.sightChecks);
    }

    @Test
    void cutAndMarkCanBeTurnedOff() {
        senses.crouching.add(A);
        marked.add(A);
        var steering = steering(new StealthSettings(1, 7, 3, 1, false, false, false, false));
        assertEquals(1, steering.weigh(match, A, 1, EnemyKind.ZOMBIE), DELTA);
        assertEquals(0, senses.crouchChecks, "nothing to work out");
    }

    @Test
    void anEnemyLosesTrackAfterThreeSecondsOutOfSight() {
        FakeEnemy zombie = enemy(EnemyKind.ZOMBIE, A);
        AggroSteering steering = steering();
        senses.see(zombie, A);
        assertTrue(steering.keepsTarget(match, zombie, A));
        tick += 20;
        assertTrue(steering.keepsTarget(match, zombie, A));
        senses.blind(zombie);
        for (int second = 1; second < 3; second++) {
            tick += 20;
            assertTrue(steering.keepsTarget(match, zombie, A), "second " + second + " out of sight");
        }
        tick += 20;
        assertFalse(steering.keepsTarget(match, zombie, A));
    }

    @Test
    void seeingTheTargetAgainRestartsTheTimer() {
        FakeEnemy zombie = enemy(EnemyKind.ZOMBIE, A);
        AggroSteering steering = steering();
        assertTrue(steering.keepsTarget(match, zombie, A));
        tick += 40;
        senses.see(zombie, A);
        assertTrue(steering.keepsTarget(match, zombie, A));
        senses.blind(zombie);
        tick += 40;
        assertTrue(steering.keepsTarget(match, zombie, A));
        tick += 20;
        assertFalse(steering.keepsTarget(match, zombie, A));
    }

    @Test
    void aNewTargetStartsANewTimer() {
        FakeEnemy zombie = enemy(EnemyKind.ZOMBIE, A);
        AggroSteering steering = steering();
        assertTrue(steering.keepsTarget(match, zombie, A));
        tick += 40;
        assertTrue(steering.keepsTarget(match, zombie, B), "switched to B");
        tick += 40;
        assertTrue(steering.keepsTarget(match, zombie, B));
        tick += 20;
        assertFalse(steering.keepsTarget(match, zombie, B));
        tick += 20;
        assertTrue(steering.keepsTarget(match, zombie, B), "chasing B again starts over");
    }

    @Test
    void kindsThatIgnoreStealthNeverLoseTrack() {
        FakeEnemy spider = enemy(EnemyKind.SPIDER, A);
        AggroSteering steering = steering(ignoring(EnemyKind.SPIDER));
        for (int second = 0; second < 10; second++) {
            assertTrue(steering.keepsTarget(match, spider, A));
            tick += 20;
        }
        assertEquals(0, senses.sightChecks);
    }

    @Test
    void theLoseTrackTimeIsConfigurable() {
        FakeEnemy zombie = enemy(EnemyKind.ZOMBIE, A);
        var steering = steering(new StealthSettings(0.25, 7, 1, 2, false, false, false, false));
        assertTrue(steering.keepsTarget(match, zombie, A));
        tick += 19;
        assertTrue(steering.keepsTarget(match, zombie, A));
        tick += 1;
        assertFalse(steering.keepsTarget(match, zombie, A));
    }

    @Test
    void anEnemyInSmokeMayNotTarget() {
        FakeEnemy zombie = enemy(EnemyKind.ZOMBIE, A);
        FakeEnemy skeleton = enemy(EnemyKind.SKELETON, A);
        senses.smoke.add(zombie);
        senses.smoke.add(skeleton);
        AggroSteering steering = steering(ignoring(EnemyKind.SKELETON));
        assertFalse(steering.mayTarget(match, zombie));
        assertFalse(steering.mayTarget(match, skeleton), "ignoring stealth does not see through smoke");
        senses.smoke.clear();
        assertTrue(steering.mayTarget(match, zombie));
    }

    @Test
    void theMatchEndForgetsItsChases() {
        FakeEnemy zombie = enemy(EnemyKind.ZOMBIE, A);
        AggroSteering steering = steering();
        assertTrue(steering.keepsTarget(match, zombie, A));
        tick += 60;
        steering.onMatchEnd(new MatchEndEvent(match,
                new MatchResult("dojo", MatchResult.Reason.STOPPED, List.of(), List.of(), List.of())));
        assertTrue(steering.keepsTarget(match, zombie, A), "a fresh chase");
    }

    @Test
    void deadEnemiesAreForgotten() {
        FakeEnemy zombie = enemy(EnemyKind.ZOMBIE, A);
        AggroSteering steering = steering();
        assertTrue(steering.keepsTarget(match, zombie, A));
        tick += 60;
        zombie.alive = false;
        // A later check for another enemy prunes the dead one; were it kept, it would lose track now.
        FakeEnemy other = enemy(EnemyKind.ZOMBIE, A);
        assertTrue(steering.keepsTarget(match, other, A));
        zombie.alive = true;
        assertTrue(steering.keepsTarget(match, zombie, A));
    }
}
