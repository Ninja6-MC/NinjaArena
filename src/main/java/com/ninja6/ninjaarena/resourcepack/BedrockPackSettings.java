package com.ninja6.ninjaarena.resourcepack;

import com.ninja6.ninjaarena.message.Messages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

/** Bedrock file pins and a mapping-installation assertion; enforcement comes from Geyser. */
public record BedrockPackSettings(boolean enabled, boolean required, boolean mappingsInstalled,
        Pack standard, Optional<Pack> detailed) {
    public static final UUID STANDARD_ID = UUID.fromString("91de2af7-4a74-4dd8-8ac9-e09b8ec83aad");
    public static final UUID DETAILED_ID = UUID.fromString("a232ba22-3d8d-4680-b5d8-35a966028dfa");
    public static final BedrockPackSettings OFF = new BedrockPackSettings(false, true, false, null, Optional.empty());

    public record Pack(String file, String sha256) {
        public Pack {
            if (file == null || !file.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.mcpack")) {
                throw new IllegalArgumentException("Bedrock file must be a plain .mcpack filename in the NinjaArena folder");
            }
            if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Bedrock SHA-256 must contain exactly 64 hexadecimal characters");
            }
            sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
        }

        public Path checkedFile(Path dataFolder) throws IOException {
            Path root = dataFolder.toRealPath();
            Path path = root.resolve(file);
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                    || !path.toRealPath().getParent().equals(root)) {
                throw new IOException("Bedrock pack must be a regular file inside the NinjaArena folder: " + file);
            }
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
                if (!MessageDigest.isEqual(digest, HexFormat.of().parseHex(sha256))) {
                    throw new IOException("Bedrock pack SHA-256 differs from its configured pin: " + file);
                }
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            return path;
        }
    }

    public BedrockPackSettings {
        java.util.Objects.requireNonNull(detailed);
        if (enabled && standard == null) {
            throw new IllegalArgumentException("Enabled Bedrock delivery needs a Standard file and SHA-256");
        }
        if (standard != null && detailed.isPresent() && standard.file().equalsIgnoreCase(detailed.get().file())) {
            throw new IllegalArgumentException("Bedrock Standard and Detailed must use different files");
        }
    }

    /** Reads the whole resource-pack section. Top-level enabled also gates Bedrock delivery. */
    public static BedrockPackSettings from(ConfigurationSection resourcePack) {
        validateRemovedKeys(resourcePack);
        if (resourcePack == null) return OFF;
        if (resourcePack.contains("bedrock") && !resourcePack.isConfigurationSection("bedrock")) {
            throw new IllegalArgumentException("resource-pack.bedrock must be a section");
        }
        ConfigurationSection section = resourcePack.getConfigurationSection("bedrock");
        if (section == null) return OFF;
        boolean mappings = bool(section, "mappings-installed", false);
        Pack standard = pack(section, "standard", false);
        Pack detailed = pack(section, "detailed", false);
        return new BedrockPackSettings(bool(resourcePack, "enabled", false) && standard != null,
                bool(resourcePack, "required", true), mappings,
                standard, Optional.ofNullable(detailed));
    }

    /** Rejects obsolete switches even when shared pack delivery is disabled. */
    public static void validateRemovedKeys(ConfigurationSection resourcePack) {
        if (resourcePack == null) return;
        for (String key : resourcePack.getKeys(true)) {
            String path = "resource-pack." + key;
            String problem = removedKeyProblem(path);
            if (problem != null) throw new IllegalArgumentException(path + ": " + problem);
        }
    }

    /** Shared by startup settings and reload's source-located validation. */
    public static String removedKeyProblem(String path) {
        return switch (path) {
            case "resource-pack.bedrock.enabled" ->
                    "removed; use resource-pack.enabled and configure resource-pack.bedrock.standard file/sha256";
            case "resource-pack.bedrock.geyser-enforces-packs" ->
                    "removed; use resource-pack.required, set gameplay.force-resource-packs: true in Geyser, and confirm resource-pack.bedrock.mappings-installed";
            default -> null;
        };
    }

    /** Server policy only; this never claims a successful client load. */
    public String vanillaReason(boolean deliveryReady, boolean enforcement) {
        String key = vanillaKey(deliveryReady, enforcement);
        return key == null ? "" : Messages.text(key);
    }

    /** The {@code pack.bedrock.reason} message for why Bedrock stays vanilla; null if it does not. */
    public String vanillaKey(boolean deliveryReady, boolean enforcement) {
        if (!enabled) return "pack.bedrock.reason.disabled";
        if (!required) return "pack.bedrock.reason.optional";
        if (!deliveryReady) return "pack.bedrock.reason.invalid-files";
        if (!mappingsInstalled) return "pack.bedrock.reason.no-mappings";
        if (!enforcement) return "pack.bedrock.reason.not-enforced";
        return null;
    }

    private static Pack pack(ConfigurationSection section, String quality, boolean required) {
        if (section.contains(quality) && !section.isConfigurationSection(quality)) {
            throw new IllegalArgumentException("resource-pack.bedrock." + quality + " must be a section");
        }
        String file = text(section, quality + ".file");
        String hash = text(section, quality + ".sha256");
        if (!required && file.isBlank() && hash.isBlank()) return null;
        try {
            return new Pack(file, hash);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("resource-pack.bedrock." + quality + ": " + e.getMessage(), e);
        }
    }

    private static boolean bool(ConfigurationSection section, String key, boolean fallback) {
        Object value = section.get(key, fallback);
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException("resource-pack.bedrock." + key + " must be a boolean");
        return bool;
    }

    private static String text(ConfigurationSection section, String key) {
        Object value = section.get(key, "");
        if (!(value instanceof String text)) throw new IllegalArgumentException("resource-pack.bedrock." + key + " must be text");
        return text;
    }
}
