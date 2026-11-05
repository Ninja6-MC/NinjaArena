package com.ninja6.ninjaarena.stats;

/** Which totals a read covers: all-time, the current season, or one season by id. */
public sealed interface StatsScope {

    StatsScope ALL_TIME = new AllTime();
    StatsScope CURRENT_SEASON = new CurrentSeason();

    static StatsScope season(int id) {
        return new OneSeason(id);
    }

    /** Every match ever recorded. */
    record AllTime() implements StatsScope {
    }

    /** The season current when the read runs. */
    record CurrentSeason() implements StatsScope {
    }

    /** One season, current or archived, by {@link Season#id()}. */
    record OneSeason(int id) implements StatsScope {

        public OneSeason {
            if (id < 1) {
                throw new IllegalArgumentException("season ids start at 1");
            }
        }
    }
}
