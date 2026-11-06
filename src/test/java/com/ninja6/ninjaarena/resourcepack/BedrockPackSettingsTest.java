package com.ninja6.ninjaarena.resourcepack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BedrockPackSettingsTest {
    @TempDir Path dir;

    private YamlConfiguration configured() {
        var config = new YamlConfiguration();
        config.set("enabled", true);
        config.set("bedrock.enabled", true);
        config.set("bedrock.standard.file", "standard.mcpack");
        config.set("bedrock.standard.sha256", "A".repeat(64));
        return config;
    }

    @Test void assertionAndDeliveryDefaultOffAndDetailedIsNeverAutomatic() {
        assertEquals(BedrockPackSettings.OFF, BedrockPackSettings.from(null));
        assertEquals(BedrockPackSettings.OFF, BedrockPackSettings.from(new YamlConfiguration()));
        var config = configured();
        var settings = BedrockPackSettings.from(config);
        assertTrue(settings.enabled());
        assertFalse(settings.geyserEnforcesPacks());
        assertTrue(settings.detailed().isEmpty());
        assertEquals("a".repeat(64), settings.standard().sha256());
        config.set("bedrock.geyser-enforces-packs", true);
        assertTrue(BedrockPackSettings.from(config).geyserEnforcesPacks());
        config.set("enabled", false);
        assertFalse(BedrockPackSettings.from(config).enabled());
    }

    @Test void pinsRequireCompleteSafeDistinctFilesAndStrictTypes() {
        for (String bad : new String[] {"../standard.mcpack", "C:/standard.mcpack", "sub/standard.mcpack", "standard.zip", ""}) {
            var config = configured();
            config.set("bedrock.standard.file", bad);
            assertThrows(IllegalArgumentException.class, () -> BedrockPackSettings.from(config), bad);
        }
        for (String key : new String[] {"bedrock.enabled", "bedrock.geyser-enforces-packs"}) {
            var config = configured(); config.set(key, "true");
            assertThrows(IllegalArgumentException.class, () -> BedrockPackSettings.from(config));
        }
        for (Object hash : new Object[] {"", "a".repeat(40), "z".repeat(64), 42}) {
            var config = configured(); config.set("bedrock.standard.sha256", hash);
            assertThrows(IllegalArgumentException.class, () -> BedrockPackSettings.from(config));
        }
        var config = configured();
        config.set("bedrock.detailed.file", "STANDARD.mcpack");
        config.set("bedrock.detailed.sha256", "b".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> BedrockPackSettings.from(config));
        config.set("bedrock.detailed.file", "detailed.mcpack");
        assertTrue(BedrockPackSettings.from(config).detailed().isPresent());
        config.set("bedrock.detailed.sha256", "");
        assertThrows(IllegalArgumentException.class, () -> BedrockPackSettings.from(config));
    }

    @Test void exactFileBytesMustMatchThePin() throws Exception {
        Path file = dir.resolve("standard.mcpack");
        Files.writeString(file, "pinned pack bytes");
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        var pack = new BedrockPackSettings.Pack("standard.mcpack", sha256);
        assertEquals(file.toRealPath(), pack.checkedFile(dir));
        Files.writeString(file, "changed pack bytes");
        assertThrows(IOException.class, () -> pack.checkedFile(dir));
        Files.delete(file);
        assertThrows(IOException.class, () -> pack.checkedFile(dir));
    }

    @Test void runtimeQualityIdentitiesMatchTheBuildSource() throws Exception {
        String source = Files.readString(Path.of("resourcepack/bedrock/target.json"));
        assertTrue(source.contains(BedrockPackSettings.STANDARD_ID.toString()));
        assertTrue(source.contains(BedrockPackSettings.DETAILED_ID.toString()));
        assertNotEquals(BedrockPackSettings.STANDARD_ID, BedrockPackSettings.DETAILED_ID);
    }
}
