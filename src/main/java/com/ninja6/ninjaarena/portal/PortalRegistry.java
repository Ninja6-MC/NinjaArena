package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * Every portal, kept in memory and in {@code portals.yml}, with a chunk index for the
 * per-move lookup. Each change is written to disk before it is accepted; a failed write
 * leaves the registry as it was. Portals never overlap, so a block belongs to at most one.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class PortalRegistry {

    private final PortalStore store;
    private final Map<String, Portal> portals = new TreeMap<>();
    private PortalIndex index = PortalIndex.EMPTY;

    /** Set when the file could not be read, so a later write cannot replace what is in it. */
    private boolean unreadable;

    public PortalRegistry(PortalStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * The registry for the running server: {@code portals.yml} in the plugin's data folder.
     * A file that cannot be read is logged and left as it is.
     */
    public static PortalRegistry open(Plugin plugin) {
        PortalStore store = new PortalStore(plugin.getDataFolder().toPath().resolve("portals.yml"));
        PortalRegistry registry = new PortalRegistry(store);
        try {
            registry.load();
            plugin.getLogger().info("Loaded " + registry.all().size() + " portal(s).");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read " + store.file()
                    + "; no portal works and portals cannot be changed until it is fixed and the server"
                    + " restarted.", e);
        }
        return registry;
    }

    /**
     * Reads {@code portals.yml}, replacing what is in memory.
     *
     * @throws IOException if the file cannot be read; the registry is then left empty and
     *                     refuses every change until a load succeeds, so the file is never
     *                     overwritten with less than it holds
     */
    public void load() throws IOException {
        portals.clear();
        index = PortalIndex.EMPTY;
        List<Portal> loaded;
        try {
            loaded = store.load();
        } catch (IOException e) {
            unreadable = true;
            throw e;
        }
        unreadable = false;
        for (Portal portal : loaded) {
            portals.put(portal.name(), portal);
        }
        index = new PortalIndex(portals.values());
    }

    public Optional<Portal> get(String name) {
        return Optional.ofNullable(portals.get(Portal.normalizeName(name)));
    }

    /** Every portal, by name. */
    public Collection<Portal> all() {
        return Collections.unmodifiableCollection(portals.values());
    }

    /** The portal holding the block, or {@code null}. */
    public Portal at(String world, int x, int y, int z) {
        return index.at(world, x, y, z);
    }

    /** The portal holding the block the location is in, or {@code null}. */
    public Portal at(Location location) {
        if (location.getWorld() == null) {
            return null;
        }
        return index.at(location.getWorld().getName(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ());
    }

    /**
     * Registers a portal. Whether the arena exists is the caller's to check.
     *
     * @throws ArenaException if the name is taken or invalid, the region is refused or
     *                        overlaps another portal, or the write fails
     */
    public Portal create(String name, String world, ArenaBounds region, String arena) throws ArenaException {
        String key = Portal.normalizeName(name);
        if (!Portal.isValidName(key)) {
            throw new ArenaException("Portal names are 1 to 32 letters, digits, _ or -.");
        }
        if (portals.containsKey(key)) {
            throw new ArenaException("A portal called " + key + " already exists.");
        }
        Portal portal;
        try {
            portal = new Portal(key, world, region, Arena.normalizeName(arena));
        } catch (IllegalArgumentException e) {
            throw new ArenaException(capitalize(e.getMessage()) + ".", e);
        }
        for (Portal other : portals.values()) {
            if (other.overlaps(portal)) {
                throw new ArenaException("That region overlaps portal " + other.name() + ".");
            }
        }
        Map<String, Portal> next = new TreeMap<>(portals);
        next.put(key, portal);
        write(next);
        return portal;
    }

    /** Removes a portal. Nothing in the world changes. */
    public void delete(String name) throws ArenaException {
        String key = Portal.normalizeName(name);
        if (!portals.containsKey(key)) {
            throw new ArenaException("No portal called " + name + ".");
        }
        Map<String, Portal> next = new TreeMap<>(portals);
        next.remove(key);
        write(next);
    }

    private void write(Map<String, Portal> next) throws ArenaException {
        if (unreadable) {
            throw new ArenaException(store.file().getFileName() + " could not be read at startup. Fix or remove it"
                    + " and restart before changing portals.");
        }
        try {
            store.save(next.values());
        } catch (IOException e) {
            throw new ArenaException("Could not save " + store.file().getFileName() + ": " + e.getMessage(), e);
        }
        portals.clear();
        portals.putAll(next);
        index = new PortalIndex(portals.values());
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "Refused";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
