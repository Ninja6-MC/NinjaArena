package com.ninja6.ninjaarena.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.event.EventSettings.Bonus;
import com.ninja6.ninjaarena.event.EventSettings.Night;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArenaEventsTest {

    private final List<Integer> notices = new ArrayList<>();
    private final TestClock clock = new TestClock();

    @Test
    void localDayAndWindowRollOverMidnight() {
        clock.set("2026-01-03T17:50:00Z"); // Saturday 23:20 in India
        ArenaEvents events = events("Asia/Kolkata", DayOfWeek.SATURDAY, "23:30");
        assertNext(events, "2026-01-03T18:00:00Z");
        clock.set("2026-01-03T19:00:00Z"); // Sunday 00:30 in India
        assertEquals(Optional.of("dojo"), events.arena());
        assertNext(events, "2026-01-10T18:00:00Z");
        clock.set("2026-01-03T19:30:00Z");
        assertTrue(events.active().isEmpty());
    }

    @Test
    void springGapShiftsForwardAndFallOverlapUsesEarlierOffsetOnce() {
        clock.set("2026-03-07T22:00:00Z");
        ArenaEvents events = events("America/New_York", DayOfWeek.SUNDAY, "02:30");
        assertNext(events, "2026-03-08T07:30:00Z");
        clock.set("2026-10-31T22:00:00Z");
        ArenaEvents overlap = events("America/New_York", DayOfWeek.SUNDAY, "01:30");
        assertNext(overlap, "2026-11-01T05:30:00Z");
        clock.set("2026-11-01T06:30:00Z");
        assertNext(overlap, "2026-11-08T06:30:00Z");
    }

    @Test
    void thresholdsFireOnceAcrossTicksAndReload() {
        clock.set("2026-01-02T18:29:59Z");
        ArenaEvents events = events("UTC", DayOfWeek.FRIDAY, "19:00");
        events.tick();
        assertTrue(notices.isEmpty());
        for (String instant : List.of("18:30:01", "18:31:00", "18:50:00", "18:59:01", "19:00:02", "19:30:00")) {
            clock.set("2026-01-02T" + instant + "Z");
            events.tick();
            events.settings(events.settings());
            events.tick();
        }
        assertEquals(List.of(30, 10, 1, 0), notices);
    }

    @Test
    void cancellationSkipsOnlyOneOccurrenceAndManualStartAnnouncesImmediately() {
        clock.set("2026-01-02T18:29:00Z");
        ArenaEvents events = events("UTC", DayOfWeek.FRIDAY, "19:00");
        assertTrue(events.cancel("night"));
        assertFalse(events.cancel("night"));
        assertNext(events, "2026-01-09T19:00:00Z");
        events.settings(events.settings());
        clock.set("2026-01-02T19:00:02Z");
        events.tick();
        assertTrue(notices.isEmpty());
        assertTrue(events.start("night"));
        assertFalse(events.start("night"));
        assertEquals(List.of(0), notices);
        assertTrue(events.active().isPresent());
        assertTrue(events.cancel("night"));
        assertTrue(events.active().isEmpty());
    }

    private ArenaEvents events(String zone, DayOfWeek day, String time) {
        Night night = new Night("night", day, LocalTime.parse(time), "dojo", Duration.ofMinutes(90), Bonus.NONE);
        return new ArenaEvents(new EventSettings(ZoneId.of(zone), List.of(30, 10, 1), null, List.of(night)),
                clock, (window, minutes, webhook) -> notices.add(minutes));
    }

    private static void assertNext(ArenaEvents events, String instant) {
        assertEquals(Instant.parse(instant), events.next().orElseThrow().start());
    }

    private static final class TestClock extends Clock {
        private Instant now;

        void set(String instant) {
            now = Instant.parse(instant);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
