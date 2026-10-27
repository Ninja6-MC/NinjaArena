package com.ninja6.ninjaarena;

import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import java.io.IOException;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for NinjaArena.
 */
public final class NinjaArenaPlugin extends JavaPlugin {

    private SnapshotService snapshots;
    private ArenaWorld arenaWorld;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            snapshots = new SnapshotService(
                    new SnapshotStore(getDataFolder().toPath().resolve("snapshots")), getLogger());
        } catch (IOException e) {
            // Without a snapshot store no player can enter an arena safely.
            getLogger().log(Level.SEVERE, "Could not open the snapshot store; disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        arenaWorld = ArenaWorld.load(ArenaWorldSettings.from(getConfig().getConfigurationSection("arena-world")), getLogger());
        getLogger().info("NinjaArena enabled.");
    }

    @Override
    public void onDisable() {
        if (arenaWorld != null) {
            arenaWorld.close();
        }
        snapshots = null;
        getLogger().info("NinjaArena disabled.");
    }

    /** Pre-entry player snapshots; {@code null} while the plugin is disabled. */
    public SnapshotService snapshots() {
        return snapshots;
    }

    public ArenaWorld arenaWorld() {
        return arenaWorld;
    }
}
