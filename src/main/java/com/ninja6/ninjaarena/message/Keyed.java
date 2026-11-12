package com.ninja6.ninjaarena.message;

import net.kyori.adventure.text.Component;

/**
 * A failure whose chat text is a messages.yml key with its placeholders, so it is shown in
 * the server's wording rather than as the exception's own message.
 */
public interface Keyed {

    String key();

    /** Placeholder name, value pairs for {@link #key()}. */
    Object[] placeholders();

    /** The text in use for this failure. */
    default Component text() {
        return Messages.msg(key(), placeholders());
    }
}
