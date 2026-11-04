package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class BlinkPlanTest {

    private static final double DELTA = 1e-9;
    private static final StalkerSettings SETTINGS = StalkerSettings.DEFAULTS;
    private static final BooleanSupplier NEVER_ASKED = () -> {
        throw new AssertionError("no path is needed for a target this far away");
    };

    @Test
    void aTargetAtTheBlinkDistanceOrFartherIsOutOfReachWithoutAPathCheck() {
        assertTrue(BlinkPlan.outOfReach(8, SETTINGS, NEVER_ASKED));
        assertTrue(BlinkPlan.outOfReach(24, SETTINGS, NEVER_ASKED));
    }

    @Test
    void aTargetBeyondTheRangeIsNeverBlinkedTo() {
        assertFalse(BlinkPlan.outOfReach(24.01, SETTINGS, NEVER_ASKED));
        assertFalse(BlinkPlan.outOfReach(Double.NaN, SETTINGS, NEVER_ASKED));
        assertFalse(BlinkPlan.outOfReach(Double.POSITIVE_INFINITY, SETTINGS, NEVER_ASKED));
    }

    @Test
    void aNearTargetIsOutOfReachOnlyWhenNoWalkingPathGetsThere() {
        int[] asked = {0};
        assertFalse(BlinkPlan.outOfReach(3, SETTINGS, () -> {
            asked[0]++;
            return false;
        }), "walkable: the melee goal takes it");
        assertTrue(BlinkPlan.outOfReach(3, SETTINGS, () -> {
            asked[0]++;
            return true;
        }), "up a pillar or across a gap");
        assertEquals(2, asked[0]);
    }

    @Test
    void aPathReachesOnlyWhenItEndsAtTheTarget() {
        assertTrue(BlinkPlan.reaches(true, true, 0.5));
        assertTrue(BlinkPlan.reaches(true, true, BlinkPlan.REACH_SLACK));
        assertFalse(BlinkPlan.reaches(true, true, 2.5), "ends short of the target");
        assertFalse(BlinkPlan.reaches(true, false, 0.5), "cannot reach its own end");
        assertFalse(BlinkPlan.reaches(false, true, 0), "no path at all");
    }

    @Test
    void theFirstSpotIsStraightBehindTheTargetFacingThem() {
        // Yaw 0 faces +z, so behind is -z.
        BlinkPlan.Spot behind = BlinkPlan.candidates(10.5, 64, 10.5, 0, 2).getFirst();
        assertEquals(10.5, behind.x(), DELTA);
        assertEquals(64, behind.y(), DELTA);
        assertEquals(8.5, behind.z(), DELTA);
        assertEquals(0, behind.yaw(), DELTA, "looks the way the target looks, at their back");

        // Yaw 90 faces -x, so behind is +x.
        BlinkPlan.Spot east = BlinkPlan.candidates(0, 70.6, 0, 90, 3).getFirst();
        assertEquals(3, east.x(), DELTA);
        assertEquals(70, east.y(), DELTA, "feet on the block the target stands in");
        assertEquals(0, east.z(), DELTA);
        assertEquals(90, east.yaw(), DELTA);
    }

    @Test
    void everySpotFacesTheTargetAtTheExpectedDistance() {
        for (float yaw : new float[] {0, 45, 90, -135, 180, 359}) {
            for (BlinkPlan.Spot spot : BlinkPlan.candidates(5, 64, -5, yaw, 2)) {
                double dx = 5 - spot.x();
                double dz = -5 - spot.z();
                double distance = Math.hypot(dx, dz);
                assertTrue(Math.abs(distance - 2) < DELTA || Math.abs(distance - 1) < DELTA, "distance " + distance);
                double radians = Math.toRadians(spot.yaw());
                assertEquals(dx / distance, -Math.sin(radians), 1e-6, "faces the target");
                assertEquals(dz / distance, Math.cos(radians), 1e-6, "faces the target");
                assertTrue(spot.yaw() > -180 && spot.yaw() <= 180);
            }
        }
    }

    @Test
    void spotsWidenTheAngleBeforeComingCloserOrChangingLevel() {
        List<BlinkPlan.Spot> spots = BlinkPlan.candidates(0, 64, 0, 0, 2);
        assertEquals(BlinkPlan.ANGLES.size() * 2 * BlinkPlan.HEIGHTS.size(), spots.size());
        assertEquals(List.of(64.0, 65.0, 63.0), spots.subList(0, 3).stream().map(BlinkPlan.Spot::y).toList(),
                "level first, then a block up, then down");
        assertEquals(1, Math.hypot(spots.get(3).x(), spots.get(3).z()), DELTA, "then closer, straight behind");
        assertEquals(30, spots.get(6).yaw(), DELTA, "then 30 degrees round");
        assertEquals(-30, spots.get(12).yaw(), DELTA);
        assertEquals(-90, spots.getLast().yaw(), DELTA, "last: beside the target");
    }

    @Test
    void aBehindDistanceOfOneHasNoCloserRing() {
        assertEquals(BlinkPlan.ANGLES.size() * BlinkPlan.HEIGHTS.size(), BlinkPlan.candidates(0, 64, 0, 0, 1).size());
        assertThrows(IllegalArgumentException.class, () -> BlinkPlan.candidates(0, 64, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlinkPlan.candidates(0, 64, 0, 0, Double.NaN));
    }

    @Test
    void landTakesTheFirstSpotThatFitsOrNone() {
        List<BlinkPlan.Spot> tried = new ArrayList<>();
        var spot = BlinkPlan.land(0, 64, 0, 0, 2, candidate -> {
            tried.add(candidate);
            return candidate.yaw() == 60;
        });
        assertEquals(60, spot.orElseThrow().yaw(), DELTA);
        assertEquals(19, tried.size(), "three angles of six spots each, then the first at 60 degrees");
        assertTrue(BlinkPlan.land(0, 64, 0, 0, 2, candidate -> false).isEmpty(), "a wall at the target's back");
    }

    @Test
    void aSpotIsInsideOnlyWhenTheWholeBodyIs() {
        var bounds = new ArenaBounds(0, 60, 0, 20, 70, 20);
        assertTrue(BlinkPlan.inside(bounds, new BlinkPlan.Spot(10.5, 64, 10.5, 0), 0.77, 2.64));
        assertFalse(BlinkPlan.inside(bounds, new BlinkPlan.Spot(0.2, 64, 10.5, 0), 0.77, 2.64), "past the west wall");
        assertFalse(BlinkPlan.inside(bounds, new BlinkPlan.Spot(20.8, 64, 10.5, 0), 0.77, 2.64), "past the east wall");
        assertTrue(BlinkPlan.inside(bounds, new BlinkPlan.Spot(10.5, 68, 10.5, 0), 0.77, 2.64), "head in the top layer");
        assertFalse(BlinkPlan.inside(bounds, new BlinkPlan.Spot(10.5, 69, 10.5, 0), 0.77, 2.64), "head above the top");
        assertFalse(BlinkPlan.inside(bounds, new BlinkPlan.Spot(10.5, 59, 10.5, 0), 0.77, 2.64), "feet below");
    }

    @Test
    void wrapKeepsYawsInOneTurn() {
        assertEquals(0, BlinkPlan.wrap(360), DELTA);
        assertEquals(180, BlinkPlan.wrap(-180), DELTA);
        assertEquals(-170, BlinkPlan.wrap(190), DELTA);
        assertEquals(170, BlinkPlan.wrap(-550), DELTA);
    }
}
