package com.ninja6.ninjaarena.resourcepack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PackStatusTest {
    @Test void reportsJavaPolicyAndBedrockFallbackInOneLine() {
        var pack = new ResourcePackSettings.Pack(UUID.randomUUID(), "https://example.test/pack.zip", "a".repeat(40));
        for (boolean required : new boolean[] {false, true}) {
            var java = new ResourcePackSettings(true, required, true, 120, pack, Optional.empty());
            assertEquals("Resource packs: Java: enabled, " + (required ? "required" : "optional")
                    + "; Bedrock: " + BedrockIntegration.NONE.status(),
                    PackStatus.describe(java, BedrockIntegration.NONE.status()));
        }
        assertTrue(PackStatus.describe(ResourcePackSettings.OFF, BedrockIntegration.NONE.status())
                .contains("Java: disabled; Bedrock: vanilla (same-server Geyser absent, disabled or unavailable)"));
    }
}
