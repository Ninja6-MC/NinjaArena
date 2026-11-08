package com.ninja6.ninjaarena.resourcepack;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/** Validated delivery configuration. Missing enabled configuration is rejected before any prompt is sent. */
public record ResourcePackSettings(boolean enabled, boolean required, boolean thrownVisuals, int timeoutSeconds,
        Pack base, Optional<Pack> detailed, boolean javaLoginGate, TextureMenuMode textureMenu) {

    public static final ResourcePackSettings OFF = new ResourcePackSettings(false, true, true, 120, null,
            Optional.empty());

    /** Settings with the default texture menu. */
    public ResourcePackSettings(boolean enabled, boolean required, boolean thrownVisuals, int timeoutSeconds,
            Pack base, Optional<Pack> detailed, boolean javaLoginGate) {
        this(enabled, required, thrownVisuals, timeoutSeconds, base, detailed, javaLoginGate,
                TextureMenuMode.DIALOG);
    }

    /** Settings without a declared login gate. */
    public ResourcePackSettings(boolean enabled, boolean required, boolean thrownVisuals, int timeoutSeconds,
            Pack base, Optional<Pack> detailed) {
        this(enabled, required, thrownVisuals, timeoutSeconds, base, detailed, false);
    }

    public record Pack(UUID id, String url, String sha1) {
        public Pack {
            Objects.requireNonNull(id, "pack id");
            Objects.requireNonNull(url, "pack URL");
            Objects.requireNonNull(sha1, "pack SHA-1");
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("URL must be an absolute HTTPS URL");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0) {
                throw new IllegalArgumentException("URL must be an absolute HTTPS URL without credentials or fragment");
            }
            if (!sha1.matches("[0-9a-fA-F]{40}")) {
                throw new IllegalArgumentException("SHA-1 must contain exactly 40 hexadecimal characters");
            }
            sha1 = sha1.toLowerCase(Locale.ROOT);
        }
    }

    public ResourcePackSettings {
        Objects.requireNonNull(detailed, "Detailed pack option");
        Objects.requireNonNull(textureMenu, "texture menu");
        if (timeoutSeconds < 1 || timeoutSeconds > 600) {
            throw new IllegalArgumentException("resource-pack.timeout-seconds must be from 1 to 600");
        }
        if (enabled && base == null) {
            throw new IllegalArgumentException("resource-pack needs a URL, SHA-1 and pack id when enabled");
        }
        if (base != null && detailed.isPresent() && base.id().equals(detailed.get().id())) {
            throw new IllegalArgumentException("resource-pack.detailed.id must differ from resource-pack.id");
        }
    }

    /** The supplied section is {@code resource-pack}, not the entire configuration. */
    public static ResourcePackSettings from(ConfigurationSection section) {
        return from(section, message -> { });
    }

    /** As {@link #from(ConfigurationSection)}; {@code warn} receives recoverable problems. */
    public static ResourcePackSettings from(ConfigurationSection section, Consumer<String> warn) {
        if (section == null) {
            return OFF;
        }
        boolean enabled = bool(section, "enabled", false);
        boolean required = bool(section, "required", true);
        boolean thrown = bool(section, "thrown-visuals", true);
        boolean loginGate = bool(section, "java-login-gate", false);
        TextureMenuMode menu = TextureMenuMode.parse(section.get("texture-menu"), warn);
        Object timeout = section.get("timeout-seconds", 120);
        if (!(timeout instanceof Integer seconds)) {
            throw new IllegalArgumentException("resource-pack.timeout-seconds must be an integer");
        }
        if (!enabled) {
            return new ResourcePackSettings(false, required, thrown, seconds, null, Optional.empty(), loginGate, menu);
        }
        if (section.contains("detailed") && !section.isConfigurationSection("detailed")) {
            throw new IllegalArgumentException("resource-pack.detailed must be a section");
        }
        Pack base = pack(section, "", "resource-pack");
        String detailedPrefix = "detailed.";
        boolean hasDetailed = !text(section, detailedPrefix + "url").isBlank()
                || !text(section, detailedPrefix + "sha1").isBlank()
                || !text(section, detailedPrefix + "id").isBlank();
        return new ResourcePackSettings(true, required, thrown, seconds, base,
                hasDetailed ? Optional.of(pack(section, detailedPrefix, "resource-pack.detailed")) : Optional.empty(),
                loginGate, menu);
    }

    private static Pack pack(ConfigurationSection section, String prefix, String path) {
        try {
            String id = text(section, prefix + "id");
            if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new IllegalArgumentException("id must be a full UUID");
            }
            return new Pack(UUID.fromString(id), text(section, prefix + "url"), text(section, prefix + "sha1"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
    }

    private static String text(ConfigurationSection section, String key) {
        Object value = section.get(key, "");
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("resource-pack." + key + " must be text");
        }
        return text;
    }

    private static boolean bool(ConfigurationSection section, String key, boolean fallback) {
        Object value = section.get(key, fallback);
        if (!(value instanceof Boolean enabled)) {
            throw new IllegalArgumentException("resource-pack." + key + " must be a boolean");
        }
        return enabled;
    }
}
