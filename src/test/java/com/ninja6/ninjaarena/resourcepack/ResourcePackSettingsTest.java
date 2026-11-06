package com.ninja6.ninjaarena.resourcepack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResourcePackSettingsTest {

    private static final String BASE_ID = "a8c0f049-f25f-48d7-b330-b23810d6a701";

    private YamlConfiguration configured() {
        var section = new YamlConfiguration();
        section.set("enabled", true);
        section.set("id", BASE_ID);
        section.set("url", "https://example.org/standard-v1.zip");
        section.set("sha1", "A".repeat(40));
        return section;
    }

    @Test
    void defaultsAreDisabledRequiredAndAllowThrownVisuals() {
        assertEquals(ResourcePackSettings.OFF, ResourcePackSettings.from(null));
        assertEquals(ResourcePackSettings.OFF, ResourcePackSettings.from(new YamlConfiguration()));
        assertFalse(ResourcePackSettings.OFF.enabled());
        assertTrue(ResourcePackSettings.OFF.required());
        assertTrue(ResourcePackSettings.OFF.thrownVisuals());
    }

    @Test
    void enabledNeedsCompleteBaseConfiguration() {
        for (String field : new String[] {"url", "sha1", "id"}) {
            var section = configured();
            section.set(field, "");
            var error = assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.from(section));
            assertTrue(error.getMessage().contains("resource-pack"));
        }
    }

    @Test
    void parsesHashesAndOptionsWithoutAutomaticallyEnablingDetailed() {
        var section = configured();
        section.set("required", false);
        section.set("thrown-visuals", false);
        section.set("timeout-seconds", 30);
        ResourcePackSettings settings = ResourcePackSettings.from(section);
        assertFalse(settings.required());
        assertFalse(settings.thrownVisuals());
        assertEquals(30, settings.timeoutSeconds());
        assertEquals(UUID.fromString(BASE_ID), settings.base().id());
        assertEquals("a".repeat(40), settings.base().sha1());
        assertTrue(settings.detailed().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://example.org/pack.zip", "https://", "https://a:b@example.org/pack.zip",
            "https://example.org/pack.zip#fragment", "https://example.org:99999/pack.zip", "file:///pack.zip"})
    void rejectsUnusableDownloadUrls(String url) {
        var section = configured();
        section.set("url", url);
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.from(section));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "g123456789012345678901234567890123456789", "a8c0f049-f25f-48d7-b330-b23810d6a70"})
    void rejectsInvalidHashes(String hash) {
        var section = configured();
        section.set("sha1", hash);
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.from(section));
    }

    @Test
    void overlayMustBeCompleteAndHaveItsOwnIdentity() {
        var section = configured();
        section.set("detailed", "invalid-section");
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.from(section));
        section.set("detailed", null);
        section.set("detailed.url", "https://example.org/detailed-v1.zip");
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.from(section));
        section.set("detailed.sha1", "b".repeat(40));
        section.set("detailed.id", BASE_ID);
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.from(section));
        UUID overlay = UUID.randomUUID();
        section.set("detailed.id", overlay.toString());
        assertEquals(overlay, ResourcePackSettings.from(section).detailed().orElseThrow().id());
    }

    @Test
    void booleansTimeoutAndIdentityHaveStrictTypesAndBounds() {
        for (String key : new String[] {"enabled", "required", "thrown-visuals"}) {
            var section = configured();
            section.set(key, "false");
            assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.from(section));
        }
        for (Object timeout : new Object[] {0, 601, 12.5, "120"}) {
            var section = configured();
            section.set("timeout-seconds", timeout);
            assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.from(section));
        }
        var section = configured();
        section.set("id", "1-1-1-1-1");
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.from(section));
    }
}
