package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * Every portal and the shared hub point, kept in memory and in {@code portals.yml}, with a
 * chunk index for the per-move lookup. Each change is written to disk before it is accepted;
 * a failed write leaves the registry as it was. Portals never overlap, so a block belongs to
 * at most one.
 *
 * <p>Player-built portals live here too, named {@value #BUILT_PREFIX} and a number, so the
 * same lookup, entry and admin commands serve both kinds.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class PortalRegistry {

    /** The start of every name the registry gives a player-built portal. */
    public static final String BUILT_PREFIX = "built-";

    private final PortalStore store;
    private final Map<String, Portal> portals = new TreeMap<>();
    private PortalIndex index = PortalIndex.EMPTY;
    private ArenaPoint hub;
    private Consumer<Portal> removed = portal -> { };

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
        hub = null;
        PortalCodec.Contents loaded;
        try {
            loaded = store.loadAll();
        } catch (IOException e) {
            unreadable = true;
            throw e;
        }
        unreadable = false;
        for (Portal portal : loaded.portals()) {
            portals.put(portal.name(), portal);
        }
        index = new PortalIndex(portals.values());
        hub = loaded.hub();
    }

    /** The shared hub point in the arena world, if an admin has set one. */
    public Optional<ArenaPoint> hub() {
        return Optional.ofNullable(hub);
    }

    /**
     * Sets or, with {@code null}, removes the shared hub point.
     *
     * @throws ArenaException if the write fails; the hub is then unchanged
     */
    public void setHub(ArenaPoint point) throws ArenaException {
        write(portals, point);
    }

    /**
     * Changes a portal's own lobby choice; {@code null} makes it use the server default.
     *
     * @throws ArenaException if there is no such portal, it is a spectator portal, or the
     *                        write fails
     */
    public Portal setLobby(String name, LobbyMode lobby) throws ArenaException {
        String key = Portal.normalizeName(name);
        Portal portal = portals.get(key);
        if (portal == null) {
            throw new ArenaException("No portal called " + name + ".");
        }
        Portal changed;
        try {
            changed = portal.withLobby(lobby);
        } catch (IllegalArgumentException e) {
            throw new ArenaException(capitalize(e.getMessage()) + ".", e);
        }
        Map<String, Portal> next = new TreeMap<>(portals);
        next.put(key, changed);
        write(next);
        return changed;
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
        return create(name, world, region, arena, false);
    }

    public Portal create(String name, String world, ArenaBounds region, String arena, boolean spectator)
            throws ArenaException {
        return create(name, world, region, arena, spectator, null);
    }

    /** @param lobby the portal's own lobby choice, or {@code null} for the server default */
    public Portal create(String name, String world, ArenaBounds region, String arena, boolean spectator,
            LobbyMode lobby) throws ArenaException {
        String key = Portal.normalizeName(name);
        if (!Portal.isValidName(key)) {
            throw new ArenaException("Portal names are 1 to 32 letters, digits, _ or -.");
        }
        if (portals.containsKey(key)) {
            throw new ArenaException("A portal called " + key + " already exists.");
        }
        Portal portal;
        try {
            portal = new Portal(key, world, region, Arena.normalizeName(arena), spectator, lobby);
        } catch (IllegalArgumentException e) {
            throw new ArenaException(capitalize(e.getMessage()) + ".", e);
        }
        return add(portal);
    }

    /**
     * Registers a player-built portal under the first free name {@value #BUILT_PREFIX}1,
     * {@value #BUILT_PREFIX}2 and so on. Limits and distances are the caller's to check.
     *
     * @throws ArenaException if the opening is refused or overlaps another portal, no name is
     *                        free, or the write fails
     */
    public Portal createBuilt(String world, ArenaBounds opening, Portal.Built built) throws ArenaException {
        String name = null;
        for (int number = 1; name == null && number <= 99_999; number++) {
            if (!portals.containsKey(BUILT_PREFIX + number)) {
                name = BUILT_PREFIX + number;
            }
        }
        if (name == null) {
            throw new ArenaException("No portal name is free.");
        }
        Portal portal;
        try {
            portal = Portal.built(name, world, opening, built);
        } catch (IllegalArgumentException e) {
            throw new ArenaException(capitalize(e.getMessage()) + ".", e);
        }
        return add(portal);
    }

    private Portal add(Portal portal) throws ArenaException {
        for (Portal other : portals.values()) {
            if (other.overlaps(portal)) {
                throw new ArenaException("That region overlaps portal " + other.name() + ".");
            }
        }
        Map<String, Portal> next = new TreeMap<>(portals);
        next.put(portal.name(), portal);
        write(next);
        return portal;
    }

    /** How many player-built portals the player owns. */
    public int ownedBy(UUID owner) {
        int owned = 0;
        for (Portal portal : portals.values()) {
            if (portal.playerBuilt() && portal.built().owner().equals(owner)) {
                owned++;
            }
        }
        return owned;
    }

    /**
     * Hands over each portal in a chunk within {@code radius} chunks of the given one, along
     * each axis, through the chunk index. A portal across several chunks may come more than once.
     */
    public void near(String world, int chunkX, int chunkZ, int radius, Consumer<Portal> found) {
        index.near(world, chunkX, chunkZ, radius, found);
    }

    /** Told each portal once it is removed, by an admin or because its frame broke. */
    public void onRemoved(Consumer<Portal> removed) {
        this.removed = Objects.requireNonNull(removed, "removed");
    }

    /** Removes a portal. Nothing in the world changes. */
    public void delete(String name) throws ArenaException {
        String key = Portal.normalizeName(name);
        Portal portal = portals.get(key);
        if (portal == null) {
            throw new ArenaException("No portal called " + name + ".");
        }
        Map<String, Portal> next = new TreeMap<>(portals);
        next.remove(key);
        write(next);
        removed.accept(portal);
    }

    private void write(Map<String, Portal> next) throws ArenaException {
        write(next, hub);
    }

    private void write(Map<String, Portal> next, ArenaPoint nextHub) throws ArenaException {
        if (unreadable) {
            throw new ArenaException(store.file().getFileName() + " could not be read at startup. Fix or remove it"
                    + " and restart before changing portals.");
        }
        try {
            store.save(next.values(), nextHub);
        } catch (IOException e) {
            throw new ArenaException("Could not save " + store.file().getFileName() + ": " + e.getMessage(), e);
        }
        if (next != portals) {
            portals.clear();
            portals.putAll(next);
        }
        index = new PortalIndex(portals.values());
        hub = nextHub;
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "Refused";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
