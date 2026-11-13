package com.ninja6.ninjaarena.arena;

/**
 * The seed and difficulty a generated map was made from: generating the seed again at the
 * difficulty gives the same map.
 *
 * @param seed       the seed the map was accepted from, which is not always the one asked
 *                   for: a search may move on to a derived seed
 * @param difficulty from 1 to 5
 */
public record MapSeed(long seed, int difficulty) {
}
