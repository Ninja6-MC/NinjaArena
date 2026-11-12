package com.ninja6.ninjaarena.resourcepack;

import com.ninja6.ninjaarena.message.Messages;
import java.util.UUID;

/** Optional same-server transport. It deliberately exposes no successful-load state. */
public interface BedrockIntegration extends AutoCloseable {
    BedrockIntegration NONE = new BedrockIntegration() {
        @Override public boolean isBedrock(UUID player) { return false; }
        @Override public boolean customHeld(UUID player) { return false; }
        @Override public void close() { }
    };

    boolean isBedrock(UUID player);

    /** Mapping assertion, required policy, verified enforcement config and a selected pack; never client load evidence. */
    boolean customHeld(UUID player);

    /** The authenticated Xbox user id Geyser reports for a connected Bedrock player; empty if unknown or blank. */
    default java.util.Optional<String> xuid(UUID player) { return java.util.Optional.empty(); }

    /** The quality frozen for this player's current connection, once negotiated. Never a load result. */
    default java.util.Optional<BedrockSelections.Quality> activeQuality(UUID player) {
        return java.util.Optional.empty();
    }

    /** Whether a complete Detailed Bedrock pack is configured and loaded for selection. */
    default boolean detailedAvailable() { return false; }

    default String status() { return Messages.text("pack.bedrock.absent"); }

    default void refreshEnforcement() { }

    default java.util.Optional<BedrockSelections> selections() { return java.util.Optional.empty(); }

    @Override void close();
}
