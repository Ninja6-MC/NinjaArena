package com.ninja6.ninjaarena.message;

/**
 * An invalid value whose message stays as written for logs and stored-file errors, and whose
 * chat text is a messages.yml key.
 */
public final class KeyedArgumentException extends IllegalArgumentException implements Keyed {

    private static final long serialVersionUID = 1L;

    private final String key;
    private final transient Object[] placeholders;

    public KeyedArgumentException(String log, String key, Object... placeholders) {
        super(log);
        this.key = key;
        this.placeholders = placeholders.clone();
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
