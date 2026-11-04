package com.ninja6.ninjaarena.stats;

import java.util.UUID;

/**
 * One place in a top list.
 *
 * @param rank   the place, from 1; ties are ordered by player UUID and do not share a rank
 * @param player the player
 * @param name   the last name recorded for the player, or {@code null} if none is known
 * @param value  the player's value of the ranked stat, always above zero
 */
public record LeaderboardEntry(int rank, UUID player, String name, int value) {
}
