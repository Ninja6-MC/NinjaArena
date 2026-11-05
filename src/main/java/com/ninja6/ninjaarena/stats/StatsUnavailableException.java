package com.ninja6.ninjaarena.stats;

/** The stats database cannot be reached or was never opened; the read or change did not run. */
public final class StatsUnavailableException extends RuntimeException {

    public StatsUnavailableException(String message) {
        super(message);
    }

    public StatsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
