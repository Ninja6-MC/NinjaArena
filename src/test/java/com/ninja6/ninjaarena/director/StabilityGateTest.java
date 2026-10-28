package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.director.MatchDirectorTest.FakeEnemy;
import com.ninja6.ninjaarena.director.StabilityGate.Guard;
import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchView;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StabilityGateTest {

    private static final double DELTA = 1e-9;
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    private final Map<MatchView, Integer> alive = new IdentityHashMap<>();
    private final List<MatchView> running = new ArrayList<>();
    private int tick;
    private double mspt = 20;
    private int reads;
    private FakeMatch dojo;
    private FakeMatch temple;

    @BeforeEach
    void setUp() {
        dojo = new FakeMatch(List.of(List.of(A), List.of(B))).combat(0);
        temple = new FakeMatch(List.of(List.of(A), List.of(B))).combat(0);
        running.add(dojo);
        running.add(temple);
    }

    private StabilityGate gate() {
        return gate(StabilitySettings.DEFAULTS);
    }

    private StabilityGate gate(StabilitySettings settings) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return new StabilityGate(settings, match -> alive.getOrDefault(match, 0), () -> running, () -> tick, () -> {
            reads++;
            return mspt;
        }, logger);
    }

    @Test
    void stopsAMatchAtItsCap() {
        var gate = gate();
        alive.put(dojo, 24);
        assertTrue(gate.maySpawn(dojo));
        alive.put(dojo, 25);
        assertFalse(gate.maySpawn(dojo));
        assertTrue(gate.maySpawn(temple), "the other match is under its own cap");
    }

    @Test
    void stopsEveryMatchAtTheGlobalCap() {
        var gate = gate();
        alive.put(dojo, 20);
        alive.put(temple, 59);
        assertTrue(gate.maySpawn(dojo));
        alive.put(temple, 60);
        assertFalse(gate.maySpawn(dojo));
        assertFalse(gate.maySpawn(temple));
    }

    @Test
    void limitsSpawnsPerServerTickAcrossMatches() {
        var gate = gate();
        for (int i = 0; i < 2; i++) {
            assertTrue(gate.maySpawn(dojo));
            gate.spawned(dojo, EnemyKind.ZOMBIE);
            assertTrue(gate.maySpawn(temple));
            gate.spawned(temple, EnemyKind.ZOMBIE);
        }
        assertFalse(gate.maySpawn(dojo));
        assertFalse(gate.maySpawn(temple));

        tick++;
        assertTrue(gate.maySpawn(dojo), "a new tick starts a new count");
    }

    @Test
    void pausesAboveFortyAndResumesBelowThirtyFive() {
        var gate = gate();
        assertTrue(gate.maySpawn(dojo));
        assertEquals(Guard.NORMAL, gate.guard());

        mspt = 40;
        tick++;
        assertTrue(gate.maySpawn(dojo), "40 ms is not above the threshold");

        mspt = 40.1;
        tick++;
        assertFalse(gate.maySpawn(dojo));
        assertEquals(Guard.PAUSED, gate.guard());
        assertEquals(0.8, gate.intensity(dojo, 0.8), DELTA, "a pause alone keeps the intensity");

        for (double reading : new double[] {38, 35}) {
            mspt = reading;
            tick++;
            assertFalse(gate.maySpawn(dojo), reading + " ms is not below the resume threshold");
        }

        mspt = 34.9;
        tick++;
        assertTrue(gate.maySpawn(dojo));
        assertEquals(Guard.NORMAL, gate.guard());

        mspt = 38;
        tick++;
        assertTrue(gate.maySpawn(dojo), "back to normal, it takes 40 ms again to pause");
    }

    @Test
    void dropsOneIntensityTierAboveFortyFiveUntilTheTickTimeRecovers() {
        var gate = gate();
        assertEquals(0.8, gate.intensity(dojo, 0.8), DELTA);

        mspt = 45.5;
        tick++;
        assertEquals(0.55, gate.intensity(dojo, 0.8), DELTA);
        assertEquals(Guard.TIER_DROPPED, gate.guard());
        assertFalse(gate.maySpawn(dojo));
        assertEquals(0, gate.intensity(dojo, 0.1), DELTA, "never below 0");
        assertEquals(0.75, gate.intensity(dojo, 1), DELTA, "OVERRUN drops too");

        mspt = 42;
        tick++;
        assertEquals(0.55, gate.intensity(dojo, 0.8), DELTA, "the drop holds until below 35 ms");
        assertEquals(Guard.TIER_DROPPED, gate.guard());

        mspt = 36;
        tick++;
        assertEquals(0.55, gate.intensity(dojo, 0.8), DELTA);

        mspt = 30;
        tick++;
        assertEquals(0.8, gate.intensity(dojo, 0.8), DELTA);
        assertEquals(Guard.NORMAL, gate.guard());
        assertTrue(gate.maySpawn(dojo));
    }

    @Test
    void goesStraightFromNormalToADroppedTierAndBackFromAPauseToADrop() {
        var gate = gate();
        mspt = 60;
        assertFalse(gate.maySpawn(dojo));
        assertEquals(Guard.TIER_DROPPED, gate.guard());

        mspt = 20;
        tick++;
        gate.maySpawn(dojo);
        mspt = 41;
        tick++;
        gate.maySpawn(dojo);
        assertEquals(Guard.PAUSED, gate.guard());
        mspt = 46;
        tick++;
        gate.maySpawn(dojo);
        assertEquals(Guard.TIER_DROPPED, gate.guard());
    }

    @Test
    void readsTheTickTimeOncePerServerTick() {
        var gate = gate();
        gate.intensity(dojo, 1);
        gate.maySpawn(dojo);
        gate.spawned(dojo, EnemyKind.ZOMBIE);
        gate.maySpawn(temple);
        assertEquals(1, reads);

        mspt = 50;
        assertTrue(gate.maySpawn(dojo), "a new reading waits for the next tick");
        tick++;
        assertFalse(gate.maySpawn(dojo));
        assertEquals(2, reads);
    }

    @Test
    void anUnreadableTickTimeChangesNothing() {
        var gate = gate();
        mspt = 50;
        gate.maySpawn(dojo);
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            mspt = bad;
            tick++;
            assertFalse(gate.maySpawn(dojo));
            assertEquals(Guard.TIER_DROPPED, gate.guard());
        }
        mspt = 10;
        tick++;
        assertTrue(gate.maySpawn(dojo));
    }

    @Test
    void customLimitsApply() {
        var gate = gate(new StabilitySettings(2, 3, 5, 1, 30, 50, 20, 0.5));
        alive.put(dojo, 3);
        assertFalse(gate.maySpawn(dojo));
        alive.put(dojo, 2);
        alive.put(temple, 3);
        assertFalse(gate.maySpawn(dojo), "2 + 3 reaches the global cap of 5");
        alive.put(temple, 2);
        assertTrue(gate.maySpawn(dojo));
        gate.spawned(dojo, EnemyKind.ZOMBIE);
        assertFalse(gate.maySpawn(temple), "one spawn per tick");

        mspt = 31;
        tick++;
        assertFalse(gate.maySpawn(dojo));
        assertEquals(0.6, gate.intensity(dojo, 0.6), DELTA);
        mspt = 51;
        tick++;
        assertEquals(0.1, gate.intensity(dojo, 0.6), DELTA);
    }

    @Test
    void aDirectorBehindTheGateSpawnsAtMostFourATickAndStopsAtTheMatchCap() {
        dojo.phase = MatchPhase.OVERRUN;
        running.remove(temple);
        List<FakeEnemy> spawned = new ArrayList<>();
        MatchDirector.Spawner spawner = (kind, target) -> {
            FakeEnemy enemy = new FakeEnemy(kind, target);
            spawned.add(enemy);
            return Optional.of(enemy);
        };
        MatchDirector[] director = new MatchDirector[1];
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        var gate = new StabilityGate(StabilitySettings.DEFAULTS, match -> StabilityGate.alive(director[0]),
                () -> running, () -> tick, () -> mspt, logger);
        director[0] = new MatchDirector(dojo, IntensitySettings.DEFAULTS, new ThreatBudget(1000), EnemyCosts.DEFAULTS,
                (match, team) -> 0, spawner, gate, TargetWeigher.NONE, () -> 0);

        director[0].tick();
        assertEquals(4, spawned.size());
        for (int second = 0; second < 10; second++) {
            tick += 20;
            director[0].tick();
        }
        assertEquals(25, spawned.size());
        assertEquals(25, StabilityGate.alive(director[0]));

        spawned.get(0).alive = false;
        spawned.get(1).alive = false;
        assertEquals(23, StabilityGate.alive(director[0]));
        tick += 20;
        director[0].tick();
        assertEquals(27, spawned.size(), "deaths free room under the cap");

        spawned.get(2).alive = false;
        mspt = 41;
        tick += 20;
        director[0].tick();
        assertEquals(27, spawned.size(), "nothing spawns while paused");
        assertEquals(1, director[0].intensity(), DELTA);

        mspt = 46;
        tick += 20;
        director[0].tick();
        assertEquals(0.75, director[0].intensity(), DELTA);
        assertEquals(27, spawned.size());

        mspt = 30;
        tick += 20;
        director[0].tick();
        assertEquals(1, director[0].intensity(), DELTA);
        assertEquals(28, spawned.size());
    }
}
