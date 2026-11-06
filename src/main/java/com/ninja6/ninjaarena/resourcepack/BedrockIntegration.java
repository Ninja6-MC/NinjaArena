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

    /** Operator assertion plus a selected pack, never evidence of client loading. */
    boolean customHeld(UUID player);

    default java.util.Optional<BedrockSelections> selections() { return java.util.Optional.empty(); }

    @Override void close();
}
