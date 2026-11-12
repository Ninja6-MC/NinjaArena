package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.world.PluginWorlds;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Draws the surface of player-built portals that have a player near them, from one task
 * every {@value #PERIOD_TICKS} ticks. No portal has a task or anything in the world of its
 * own, so a portal that is removed, in a world that unloads or in an unloaded chunk simply
 * stops being drawn, and nothing is left to clean up beyond {@link PortalSurface#hide}.
 *
 * <p><b>Cost.</b> A pass looks up the portals within {@value #RANGE_CHUNKS} chunks of each
 * online player outside the arena world through the registry's chunk index, never by
 * scanning blocks, and draws at most {@value #MAX_PORTALS} of them. A portal is drawn only
 * while its chunk is loaded.
 *
 * <p><b>Frame checks.</b> The first time a portal comes near a player, and then every
 * {@value #CHECK_TICKS} ticks while it stays near, its frame is checked
 * ({@link PlayerPortals#verify}), which reads its frame blocks only. A broken frame removes
 * the portal before it is drawn. This catches what no block event reports, such as a frame
 * changed while the server was down or by a world editor.
 *
 * <p>Server thread only.
 */
public final class PortalCurtains implements Runnable {

    static final long PERIOD_TICKS = 4;
    static final int RANGE_CHUNKS = 2;
    static final int MAX_PORTALS = 16;
    static final long CHECK_TICKS = 100;

    private final PortalRegistry portals;
    private final PlayerPortals rules;
    private final Supplier<? extends Collection<? extends Player>> players;
    private final Function<String, World> worlds;
    private final PortalSurface surface;
    private final LongSupplier tick;

    /** Portals drawn in the last pass, with the tick their frame was last checked. */
    private Map<Portal, Long> shown = new HashMap<>();
    private BukkitTask task;

    /**
     * @param players the online players, {@code Server#getOnlinePlayers} on a server
     * @param worlds  a loaded world by name, or {@code null}
     * @param tick    the current server tick
     */
    public PortalCurtains(PortalRegistry portals, PlayerPortals rules, Supplier<? extends Collection<? extends Player>> players,
            Function<String, World> worlds, PortalSurface surface, LongSupplier tick) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.players = Objects.requireNonNull(players, "players");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.surface = Objects.requireNonNull(surface, "surface");
        this.tick = Objects.requireNonNull(tick, "tick");
        portals.onRemoved(this::removed);
    }

    public void start(Plugin plugin) {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this, PERIOD_TICKS, PERIOD_TICKS);
        }
    }

    /** Stops drawing and hides every portal. */
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        shown.keySet().forEach(surface::hide);
        shown = new HashMap<>();
        surface.close();
    }

    /** One pass. */
    @Override
    public void run() {
        Set<Portal> near = new LinkedHashSet<>();
        for (Player player : players.get()) {
            if (near.size() >= MAX_PORTALS) {
                break;
            }
            Location location = player.getLocation();
            World world = location.getWorld();
            if (world == null || PluginWorlds.isPluginWorld(world)) {
                continue;
            }
            portals.near(world.getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4, RANGE_CHUNKS, portal -> {
                if (portal.playerBuilt() && near.size() < MAX_PORTALS) {
                    near.add(portal);
                }
            });
        }
        long now = tick.getAsLong();
        Map<Portal, Long> drawn = new HashMap<>();
        for (Portal portal : near) {
            World world = worlds.apply(portal.world());
            if (world == null || !world.isChunkLoaded(portal.region().minChunkX(), portal.region().minChunkZ())) {
                continue;
            }
            Long checked = shown.get(portal);
            if (checked == null || now - checked >= CHECK_TICKS) {
                if (!rules.verify(portal, world)) {
                    continue;
                }
                checked = now;
            }
            drawn.put(portal, checked);
            surface.draw(world, portal);
        }
        for (Portal portal : shown.keySet()) {
            if (!drawn.containsKey(portal)) {
                surface.hide(portal);
            }
        }
        shown = drawn;
    }

    /** How many portals the last pass drew, for tests. */
    int shown() {
        return shown.size();
    }

    private void removed(Portal portal) {
        if (shown.remove(portal) != null) {
            surface.hide(portal);
        }
    }
}
