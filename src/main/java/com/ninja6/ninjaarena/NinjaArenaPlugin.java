package com.ninja6.ninjaarena;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for NinjaArena.
 */
public final class NinjaArenaPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("NinjaArena enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("NinjaArena disabled.");
    }
}
