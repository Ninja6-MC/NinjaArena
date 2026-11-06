package com.ninja6.ninjaarena.resourcepack.geyser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeyserConfigSourceTest {
    @TempDir Path dir;

    private PluginManager manager(Plugin plugin) {
        return (PluginManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PluginManager.class},
                (proxy, method, args) -> {
                    assertEquals("getPlugin", method.getName());
                    assertEquals("Geyser-Spigot", args[0]);
                    return plugin;
                });
    }

    private Plugin geyser(boolean enabled) {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> enabled;
                    case "getDataFolder" -> {
                        if (!enabled) throw new AssertionError("disabled Geyser must not access its folder");
                        yield dir.toFile();
                    }
                    default -> throw new AssertionError(method.getName());
                });
    }

    @Test void absentOrDisabledGeyserDoesNotAccessADataFolder() {
        assertNull(GeyserConfigSource.find(manager(null)));
        assertNull(GeyserConfigSource.find(manager(geyser(false))));
    }

    @Test void usesTheBukkitPluginsActualDataFolderAndOnlyReadsItsFile() throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "gameplay: {force-resource-packs: true}");
        var source = GeyserConfigSource.find(manager(geyser(true)));
        assertEquals(file, source.file());
        byte[] before = Files.readAllBytes(file);
        try (var stream = source.open()) {
            assertEquals(new String(before, java.nio.charset.StandardCharsets.UTF_8),
                    new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(file));
    }
}
