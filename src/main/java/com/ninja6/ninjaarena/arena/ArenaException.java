package com.ninja6.ninjaarena.arena;

/**
 * A registry operation that was refused or failed. The message is written for the admin
 * who ran the command.
 */
public final class ArenaException extends Exception {

    private static final long serialVersionUID = 1L;

    public ArenaException(String message) {
        super(message);
    }

    public ArenaException(String message, Throwable cause) {
        super(message, cause);
    }
}
