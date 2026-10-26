package com.ninja6.ninjaarena;

import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for NinjaArena.
 */
public final class NinjaArenaPlugin extends JavaPlugin {

    private ArenaWorld arenaWorld;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        arenaWorld = ArenaWorld.load(ArenaWorldSettings.from(getConfig().getConfigurationSection("arena-world")), getLogger());
        getLogger().info("NinjaArena enabled.");
    }

    @Override
    public void onDisable() {
        if (arenaWorld != null) {
            arenaWorld.close();
        }
        getLogger().info("NinjaArena disabled.");
    }

    public ArenaWorld arenaWorld() {
        return arenaWorld;
    }
}
