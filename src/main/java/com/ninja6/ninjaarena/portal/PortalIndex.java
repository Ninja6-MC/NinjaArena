package com.ninja6.ninjaarena.portal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the portal at a block in a few map lookups: each portal is filed under every chunk
 * its region touches, so a move checks only the portals of the chunk it lands in, never
 * every portal. Immutable; the registry builds a new one whenever the portals change.
 */
final class PortalIndex {

    static final PortalIndex EMPTY = new PortalIndex(List.of());

    private final Map<String, Map<Long, List<Portal>>> byWorld = new HashMap<>();

    PortalIndex(Collection<Portal> portals) {
        for (Portal portal : portals) {
            Map<Long, List<Portal>> chunks = byWorld.computeIfAbsent(portal.world(), world -> new HashMap<>());
            for (int x = portal.region().minChunkX(); x <= portal.region().maxChunkX(); x++) {
                for (int z = portal.region().minChunkZ(); z <= portal.region().maxChunkZ(); z++) {
                    chunks.computeIfAbsent(key(x, z), k -> new ArrayList<>(1)).add(portal);
                }
            }
        }
    }

    /** The portal whose region holds the block, or {@code null}. Portals never overlap. */
    Portal at(String world, int x, int y, int z) {
        Map<Long, List<Portal>> chunks = byWorld.get(world);
        if (chunks == null) {
            return null;
        }
        List<Portal> candidates = chunks.get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
        if (candidates == null) {
            return null;
        }
        for (Portal portal : candidates) {
            if (portal.region().contains(x, y, z)) {
                return portal;
            }
        }
        return null;
    }

    /** How many chunk entries the index holds, for tests. */
    int chunkEntries() {
        int entries = 0;
        for (Map<Long, List<Portal>> chunks : byWorld.values()) {
            entries += chunks.size();
        }
        return entries;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
