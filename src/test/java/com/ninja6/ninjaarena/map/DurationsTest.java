package com.ninja6.ninjaarena.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void readsAmountsWithUnitsLargestFirstAndZero() {
        assertEquals(Optional.of(Duration.ofMinutes(30)), Durations.parse("30m"));
        assertEquals(Optional.of(Duration.ofHours(2)), Durations.parse("2h"));
        assertEquals(Optional.of(Duration.ofMinutes(90)), Durations.parse("1h30m"));
        assertEquals(Optional.of(Duration.ofSeconds(45)), Durations.parse("45s"));
        assertEquals(Optional.of(Duration.ofDays(1).plusSeconds(5)), Durations.parse("1d5s"));
        assertEquals(Optional.of(Duration.ofHours(2)), Durations.parse(" 2H "));
        assertEquals(Optional.of(Duration.ZERO), Durations.parse("0"));
        assertEquals(Optional.of(Duration.ZERO), Durations.parse(0));
        assertEquals(Optional.of(Duration.ofDays(365)), Durations.parse("365d"));
    }

    @Test
    void refusesAnythingElse() {
        for (Object value : List.of("", "30", "m", "30x", "30m1h", "1h1h", "-5m", "1.5h", "366d", "30 m", 5, 1.5,
                true)) {
            assertEquals(Optional.empty(), Durations.parse(value), String.valueOf(value));
        }
        assertEquals(Optional.empty(), Durations.parse(null));
    }

    @Test
    void formatsWhatItReads() {
        for (String text : List.of("0", "30m", "2h", "1h30m", "1d5s", "365d")) {
            assertEquals(text, Durations.format(Durations.parse(text).orElseThrow()));
        }
    }
}
