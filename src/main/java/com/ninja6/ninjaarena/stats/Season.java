package com.ninja6.ninjaarena.stats;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A stats season. Exactly one is current; starting another archives it, and an archived
 * season keeps its stats and stays readable.
 *
 * @param id        the season's number, from 1, in the order seasons started
 * @param name      the unique name it was started with
 * @param startedAt when it started
 * @param endedAt   when it was archived, or {@code null} while it is current
 */
public record Season(int id, String name, Instant startedAt, Instant endedAt) {

    public Season {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(startedAt, "startedAt");
    }

    public boolean isCurrent() {
        return endedAt == null;
    }

    public Optional<Instant> ended() {
        return Optional.ofNullable(endedAt);
    }
}
