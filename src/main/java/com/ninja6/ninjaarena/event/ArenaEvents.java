package com.ninja6.ninjaarena.event;

import com.ninja6.ninjaarena.event.EventSettings.Night;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Main-thread schedule and temporary administrative overrides. */
public final class ArenaEvents {

    public record Window(Night night, Instant start) {
        public Instant end() {
            return start.plus(night.duration());
        }

        boolean contains(Instant now) {
            return !now.isBefore(start) && now.isBefore(end());
        }
    }

    @FunctionalInterface
    public interface Announcement {
        void send(Window window, int minutes, URI webhook);
    }

    private static final Comparator<Window> ORDER =
            Comparator.comparing(Window::start).thenComparing(window -> window.night().id());

    private final Clock clock;
    private final Announcement announce;
    private final Set<Window> cancelled = new HashSet<>();
    private EventSettings settings;
    private Instant checked;
    private Window manual;

    public ArenaEvents(EventSettings settings, Clock clock, Announcement announce) {
        this.settings = settings;
        this.clock = clock;
        this.announce = announce;
        checked = clock.instant();
    }

    public void settings(EventSettings next) {
        if (!settings.zone().equals(next.zone())) {
            cancelled.clear();
        } else {
            cancelled.removeIf(window -> !next.schedule().contains(window.night()));
        }
        if (manual != null && !next.schedule().contains(manual.night())) {
            manual = null;
        }
        settings = next;
    }

    public EventSettings settings() {
        return settings;
    }

    public Optional<Window> active() {
        Instant now = clock.instant();
        if (manual != null && manual.contains(now)) {
            return Optional.of(manual);
        }
        return windows(now).filter(window -> window.contains(now)).min(ORDER);
    }

    public Optional<String> arena() {
        return active().map(window -> window.night().arena());
    }

    public EventSettings.Bonus bonus(String arena) {
        return active().filter(window -> window.night().arena().equals(arena))
                .map(window -> window.night().bonus()).orElse(EventSettings.Bonus.NONE);
    }

    public Optional<Window> next() {
        Instant now = clock.instant();
        return windows(now).filter(window -> window.start().isAfter(now)).min(ORDER);
    }

    public boolean start(String id) {
        if (active().isPresent()) {
            return false;
        }
        for (Night night : settings.schedule()) {
            if (night.id().equals(id)) {
                manual = new Window(night, clock.instant());
                announce.send(manual, 0, settings.webhook());
                return true;
            }
        }
        return false;
    }

    public boolean cancel(String id) {
        Instant now = clock.instant();
        if (manual != null && manual.contains(now) && manual.night().id().equals(id)) {
            manual = null;
            return true;
        }
        return occurrences(now).filter(window -> window.night().id().equals(id) && window.end().isAfter(now))
                .min(ORDER).map(cancelled::add).orElse(false);
    }

    /** Broadcast the latest threshold crossed for each occurrence; skip expired notices. */
    public void tick() {
        Instant now = clock.instant();
        if (!now.isAfter(checked)) {
            return;
        }
        Instant previous = checked;
        checked = now;
        cancelled.removeIf(window -> !window.end().isAfter(now));
        windows(now).sorted(ORDER).forEach(window -> {
            if (window.contains(now) && window.start().isAfter(previous)) {
                announce.send(window, 0, settings.webhook());
            } else if (window.start().isAfter(now)) {
                settings.leads().stream().filter(minutes -> {
                    Instant due = window.start().minusSeconds(60L * minutes);
                    return due.isAfter(previous) && !due.isAfter(now);
                }).min(Integer::compareTo).ifPresent(minutes -> announce.send(window, minutes, settings.webhook()));
            }
        });
    }

    private Stream<Window> windows(Instant now) {
        return occurrences(now).filter(window -> !cancelled.contains(window));
    }

    private Stream<Window> occurrences(Instant now) {
        LocalDate date = now.atZone(settings.zone()).toLocalDate();
        return settings.schedule().stream().flatMap(night -> {
            LocalDate thisWeek = date.with(TemporalAdjusters.previousOrSame(night.day()));
            return Stream.of(thisWeek.minusWeeks(1), thisWeek, thisWeek.plusWeeks(1), thisWeek.plusWeeks(2))
                    .map(day -> new Window(night, day.atTime(night.time()).atZone(settings.zone()).toInstant()));
        });
    }
}
