package com.ninja6.ninjaarena.match;

import com.ninja6.ninjaarena.arena.MapSeed;
import java.util.Objects;
import java.util.Optional;

/**
 * A map a match was played on, and the rounds played on it. A match plays one map, unless
 * its map is generated again between rounds; then each map has its own entry.
 *
 * @param arena      the map's name: a hand-built arena's, or a generated map's slot name,
 *                   which is not stable and names a different map in the next match
 * @param seed       what a generated map was made from; {@code null} for a hand-built arena
 * @param firstRound the first round played on it, from 1
 * @param rounds     the rounds played on it, extra rounds included
 */
public record PlayedMap(String arena, MapSeed seed, int firstRound, int rounds) {

    public PlayedMap {
        Objects.requireNonNull(arena, "arena");
        if (firstRound < 1 || rounds < 1) {
            throw new IllegalArgumentException("firstRound and rounds start at 1");
        }
    }

    /** The seed of a generated map; empty for a hand-built arena. */
    public Optional<MapSeed> generated() {
        return Optional.ofNullable(seed);
    }

    /** The last round played on it. */
    public int lastRound() {
        return firstRound + rounds - 1;
    }
}
