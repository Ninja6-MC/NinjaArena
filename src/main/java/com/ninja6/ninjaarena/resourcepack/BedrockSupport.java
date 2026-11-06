package com.ninja6.ninjaarena.resourcepack;

import java.util.function.Supplier;

/** Keeps optional platform linkage behind an enabled-plugin check. */
public final class BedrockSupport {
    private BedrockSupport() { }

    public static BedrockIntegration start(boolean geyserEnabled, Supplier<BedrockIntegration> factory) {
        return geyserEnabled ? factory.get() : BedrockIntegration.NONE;
    }
}
