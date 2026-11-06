package com.ninja6.ninjaarena.resourcepack.geyser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** A config-file check, not a public Geyser API or a per-player pack-load observation. */
public final class GeyserEnforcement {
    static final int MAX_BYTES = 256 * 1024;
    static final String KEY = "gameplay.force-resource-packs";
    private final boolean declared;
    private final Supplier<GeyserConfigSource> sources;
    private final Consumer<String> warning;
    private volatile boolean allowed;

    public GeyserEnforcement(boolean declared, Supplier<GeyserConfigSource> sources, Consumer<String> warning) {
        this.declared = declared;
        this.sources = sources;
        this.warning = warning;
    }

    public boolean allowed() { return allowed; }

    /** Called at enable and after a successful reload, never for individual players. */
    public void evaluate() {
        allowed = false;
        if (!declared) return;
        GeyserConfigSource source = sources.get();
        if (source == null) return; // Geyser absent/disabled: no file access or warning.
        String problem;
        try (var input = source.open()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                problem = "config exceeds the 256 KiB limit";
            } else {
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                options.setCodePointLimit(MAX_BYTES);
                options.setNestingDepthLimit(32);
                options.setMaxAliasesForCollections(10);
                Object root = new Yaml(new SafeConstructor(options)).load(text);
                if (root instanceof Map<?, ?> map && map.get("gameplay") instanceof Map<?, ?> gameplay
                        && Boolean.TRUE.equals(gameplay.get("force-resource-packs"))) {
                    allowed = true;
                    return;
                }
                problem = "setting is missing or is not boolean true";
            }
        } catch (IOException | RuntimeException e) {
            allowed = false;
            // Do not include parser snippets: Geyser's config can contain credentials.
            problem = "config is unreadable or malformed";
        }
        warning.accept("Bedrock held models disabled: " + source.file().toAbsolutePath() + " must set " + KEY
                + " to true (" + problem + "). Check Geyser's config, then reload NinjaArena.");
    }
}
