package com.ninja6.ninjaarena.resourcepack;

import java.util.UUID;

/** Optional same-server transport. It deliberately exposes no successful-load state. */
public interface BedrockIntegration extends AutoCloseable {
    BedrockIntegration NONE = new BedrockIntegration() {
        @Override public boolean isBedrock(UUID player) { return false; }
        @Override public boolean customHeld(UUID player) { return false; }
        @Override public void close() { }
    };

    boolean isBedrock(UUID player);

    /** Operator declaration, verified enforcement config and a selected pack; never client load evidence. */
    boolean customHeld(UUID player);

    default void refreshEnforcement() { }

    default java.util.Optional<BedrockSelections> selections() { return java.util.Optional.empty(); }

    @Override void close();
}
