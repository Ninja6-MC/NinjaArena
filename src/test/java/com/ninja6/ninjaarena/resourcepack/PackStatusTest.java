package com.ninja6.ninjaarena.resourcepack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PackStatusTest {
    @Test void reportsJavaPolicyAccountsAndBedrockFallbackInOneLine() {
        var pack = new ResourcePackSettings.Pack(UUID.randomUUID(), "https://example.test/pack.zip", "a".repeat(40));
        for (boolean required : new boolean[] {false, true}) {
            var java = new ResourcePackSettings(true, required, true, 120, pack, Optional.empty());
            assertEquals("Resource packs: Java: enabled, " + (required ? "required" : "optional")
                    + "; Java accounts: not authenticated: choices last one connection"
                    + "; Bedrock: " + BedrockIntegration.NONE.status(),
                    PackStatus.describe(java, BedrockIntegration.NONE.status(), JavaAuthentication.NONE));
        }
        assertTrue(PackStatus.describe(ResourcePackSettings.OFF, BedrockIntegration.NONE.status(),
                JavaAuthentication.NONE)
                .contains("Java: disabled; Java accounts: not authenticated: choices last one connection;"
                        + " Bedrock: vanilla (same-server Geyser absent, disabled or unavailable)"));
    }

    @Test void saysWhyJavaAccountsAreTreatedAsAuthenticated() {
        assertEquals(JavaAuthentication.ONLINE_MODE, JavaAuthentication.resolve(true, true, true));
        assertEquals(JavaAuthentication.ONLINE_PROXY, JavaAuthentication.resolve(false, true, true));
        assertEquals(JavaAuthentication.DECLARED_LOGIN_GATE, JavaAuthentication.resolve(false, false, true));
        assertEquals(JavaAuthentication.NONE, JavaAuthentication.resolve(false, false, false));
        assertTrue(JavaAuthentication.ONLINE_MODE.authenticated());
        assertTrue(JavaAuthentication.ONLINE_PROXY.authenticated());
        assertTrue(JavaAuthentication.DECLARED_LOGIN_GATE.authenticated());
        assertFalse(JavaAuthentication.NONE.authenticated());
        assertEquals("authenticated (online mode)", JavaAuthentication.ONLINE_MODE.describe());
        assertEquals("authenticated (online proxy)", JavaAuthentication.ONLINE_PROXY.describe());
        assertEquals("authenticated (operator-declared login gate)", JavaAuthentication.DECLARED_LOGIN_GATE.describe());
        assertEquals("not authenticated: choices last one connection", JavaAuthentication.NONE.describe());
    }
}
