package com.ninja6.ninjaarena.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

/** Loaded only after checking that PlaceholderAPI is enabled. */
public final class NinjaArenaExpansion extends PlaceholderExpansion {

    private final StatsPlaceholders placeholders;
    private final String version;

    private NinjaArenaExpansion(StatsPlaceholders placeholders, String version) {
        this.placeholders = placeholders;
        this.version = version;
    }

    public static Runnable install(JavaPlugin plugin, StatsPlaceholders placeholders) {
        NinjaArenaExpansion expansion = new NinjaArenaExpansion(placeholders, plugin.getPluginMeta().getVersion());
        if (expansion.register()) {
            return expansion::unregister;
        }
        plugin.getLogger().warning("Could not register the NinjaArena PlaceholderAPI expansion.");
        return () -> { };
    }

    @Override
    public String getIdentifier() {
        return "ninjaarena";
    }

    @Override
    public String getAuthor() {
        return "Ninja6-MC";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return placeholders.resolve(player == null ? null : player.getUniqueId(), params);
    }
}
