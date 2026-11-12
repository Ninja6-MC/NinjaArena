package com.ninja6.ninjaarena.arena;

import com.ninja6.ninjaarena.message.Keyed;
import com.ninja6.ninjaarena.message.Messages;

/**
 * A registry operation that was refused or failed, for the admin who ran the command. It
 * names a messages.yml key; {@link #getMessage()} is that text as it read when thrown.
 */
public final class ArenaException extends Exception implements Keyed {

    private static final long serialVersionUID = 1L;

    private final String key;
    private final transient Object[] placeholders;

    public ArenaException(String key, Object... placeholders) {
        this(null, key, placeholders);
    }

    public ArenaException(Throwable cause, String key, Object... placeholders) {
        super(Messages.text(key, placeholders), cause);
        this.key = key;
        this.placeholders = placeholders.clone();
    }

    /** A change the arena, portal or world refused, in that refusal's own wording. */
    public static ArenaException refused(RuntimeException e) {
        return e instanceof Keyed keyed ? new ArenaException(e, keyed.key(), keyed.placeholders())
                : new ArenaException(e, "command.failed", "reason", String.valueOf(e.getMessage()));
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public Object[] placeholders() {
        return placeholders.clone();
    }
}
