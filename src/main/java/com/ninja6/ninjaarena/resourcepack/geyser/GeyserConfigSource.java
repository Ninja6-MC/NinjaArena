package com.ninja6.ninjaarena.resourcepack.geyser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.plugin.PluginManager;

/** Read-only config access, replaceable without loading Geyser API classes. */
public interface GeyserConfigSource {
    Path file();
    InputStream open() throws IOException;

    /** Resolve the enabled plugin's own data folder, never an assumed sibling directory. */
    static GeyserConfigSource find(PluginManager plugins) {
        var geyser = plugins.getPlugin("Geyser-Spigot");
        if (geyser == null || !geyser.isEnabled()) return null;
        Path file = geyser.getDataFolder().toPath().resolve("config.yml");
        return new GeyserConfigSource() {
            @Override public Path file() { return file; }
            @Override public InputStream open() throws IOException { return Files.newInputStream(file); }
        };
    }
}
