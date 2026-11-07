package com.ninja6.ninjaarena.texture;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The identity a texture preference is stored under. A Java key holds an authenticated Java
 * account UUID and a Bedrock key an authenticated Xbox user id. The two namespaces never
 * merge, and nothing here is derived from a player name.
 */
public record PlayerKey(Edition edition, String id) {

    public enum Edition {
        JAVA("java"), BEDROCK("bedrock");

        private final String prefix;

        Edition(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    private static final Pattern XUID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public PlayerKey {
        Objects.requireNonNull(edition, "edition");
        Objects.requireNonNull(id, "id");
        if (edition == Edition.BEDROCK) {
            if (!XUID.matcher(id).matches()) {
                throw new IllegalArgumentException("A Bedrock XUID is 1 to 20 digits");
            }
        } else {
            if (!UUID_TEXT.matcher(id).matches()) {
                throw new IllegalArgumentException("A Java id is a full account UUID");
            }
            id = id.toLowerCase(Locale.ROOT);
        }
    }

    public static PlayerKey java(UUID account) {
        return new PlayerKey(Edition.JAVA, Objects.requireNonNull(account, "account").toString());
    }

    public static PlayerKey bedrock(String xuid) {
        if (xuid == null || xuid.isBlank()) {
            throw new IllegalArgumentException("A Bedrock XUID is required");
        }
        return new PlayerKey(Edition.BEDROCK, xuid.strip());
    }

    /** Parses the id an admin typed for {@code edition}. */
    public static PlayerKey of(Edition edition, String text) {
        Objects.requireNonNull(text, "text");
        return new PlayerKey(edition, text.strip());
    }

    /** Parses a stored key such as {@code java:<uuid>} or {@code bedrock:<xuid>}. */
    public static PlayerKey parse(String stored) {
        int colon = stored.indexOf(':');
        if (colon < 1) {
            throw new IllegalArgumentException("Not a player key: " + stored);
        }
        String prefix = stored.substring(0, colon);
        for (Edition edition : Edition.values()) {
            if (edition.prefix().equals(prefix)) {
                return of(edition, stored.substring(colon + 1));
            }
        }
        throw new IllegalArgumentException("Not a player key: " + stored);
    }

    /** The Java account, or empty for a Bedrock key. */
    public Optional<UUID> account() {
        return edition == Edition.JAVA ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }

    @Override
    public String toString() {
        return edition.prefix() + ":" + id;
    }
}
