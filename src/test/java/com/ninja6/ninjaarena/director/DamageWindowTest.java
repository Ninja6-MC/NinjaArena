package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DamageWindowTest {

    @Test
    void keepsTheLastSecondsOnly() {
        var window = new DamageWindow(3);
        window.add(4);
        window.add(1);
        assertEquals(5, window.total());
        window.advance();
        window.add(2);
        window.advance();
        assertEquals(7, window.total());
        window.advance();
        assertEquals(2, window.total());
        window.advance();
        assertEquals(0, window.total());
    }

    @Test
    void ignoresNegativeAndNonFiniteDamage() {
        var window = new DamageWindow(1);
        window.add(-3);
        window.add(Double.NaN);
        window.add(Double.POSITIVE_INFINITY);
        window.add(0);
        assertEquals(0, window.total());
        window.add(2.5);
        assertEquals(2.5, window.total());
        window.advance();
        assertEquals(0, window.total());
    }

    @Test
    void clearForgetsEverything() {
        var window = new DamageWindow(5);
        window.add(3);
        window.advance();
        window.add(3);
        window.clear();
        assertEquals(0, window.total());
    }

    @Test
    void needsAtLeastASecond() {
        assertThrows(IllegalArgumentException.class, () -> new DamageWindow(0));
    }
}
