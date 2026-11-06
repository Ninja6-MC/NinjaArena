package com.ninja6.ninjaarena.resourcepack;

import java.util.HexFormat;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Bukkit transport and event adapter; delivery state itself needs neither a server nor a client. */
public final class BukkitResourcePacks implements Listener, AutoCloseable {

    private final Plugin plugin;
    private final ResourcePacks packs;
    private BukkitTask timer;
    private boolean closed;

    public BukkitResourcePacks(Plugin plugin, ResourcePackSettings settings) {
        this.plugin = plugin;
        packs = new ResourcePacks(settings, new ResourcePacks.Transport() {
            @Override
            public void send(UUID id, ResourcePackSettings.Pack pack, boolean required) {
                Player player = plugin.getServer().getPlayer(id);
                if (player != null) {
                    player.addResourcePack(pack.id(), pack.url(), HexFormat.of().parseHex(pack.sha1()),
                            "NinjaArena tool visuals", required);
                }
            }

            @Override
            public void remove(UUID player, UUID pack) {
                Player online = plugin.getServer().getPlayer(player);
                if (online != null) {
                    online.removeResourcePack(pack);
                }
            }

            @Override
            public void kick(UUID player, String message) {
                Player online = plugin.getServer().getPlayer(player);
                if (online != null && online.isConnected()) {
                    online.kick(Component.text(message));
                }
            }

            @Override
            public void error(String message, RuntimeException failure) {
                plugin.getLogger().warning(message + " (" + failure.getClass().getSimpleName() + ")");
            }

            @Override
            public void warn(String message) {
                plugin.getLogger().warning(message);
            }
        }, () -> Integer.toUnsignedLong(Bukkit.getCurrentTick()));
    }

    public ResourcePacks packs() {
        return packs;
    }

    public void start() {
        if (closed || timer != null) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, packs::tick, 20, 20);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!closed) {
            packs.join(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        packs.quit(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        UUID pack = event.getID();
        var status = event.getStatus();
        Runnable apply = () -> {
            if (!closed) {
                packs.status(player, pack, status);
            }
        };
        if (event.isAsynchronous()) {
            plugin.getServer().getScheduler().runTask(plugin, apply);
        } else {
            apply.run();
        }
    }

    @Override
    public void close() {
        closed = true;
        if (timer != null) {
            timer.cancel();
        }
        packs.close();
    }
}
