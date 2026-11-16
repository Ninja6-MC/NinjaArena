package com.ninja6.ninjaarena.lobby;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A lobby world in memory: its saved records survive "restarts", and its blocks are a map. */
public final class MemorySite implements LobbyBuild.Site, LobbyPlacement.Blocks {

    public String saved;
    public boolean legacy;
    public boolean noticed;
    public boolean resetQueued;
    /** Boxes held and not yet released. */
    public final List<String> held = new ArrayList<>();
    public long seed = 7;
    public final Map<Long, String> world = new HashMap<>();
    public final List<LobbyPiece.Cushion> cushions = new ArrayList<>();
    public final List<String> entityClears = new ArrayList<>();
    /** Block writes so far, in order. */
    public long writes;
    /** When set, every block write fails with this message. */
    public String failing;
    /** Block writes after which every write fails, as when the server stops mid-way. */
    public long failAfter = Long.MAX_VALUE;

    @Override
    public Optional<String> saved() {
        return Optional.ofNullable(saved);
    }

    @Override
    public void save(String value) {
        saved = value;
    }

    @Override
    public boolean legacy() {
        return legacy;
    }

    @Override
    public void dropLegacy() {
        legacy = false;
    }

    @Override
    public boolean noticed() {
        return noticed;
    }

    @Override
    public void markNoticed() {
        noticed = true;
    }

    @Override
    public boolean resetQueued() {
        return resetQueued;
    }

    @Override
    public void queueReset(boolean queued) {
        resetQueued = queued;
    }

    @Override
    public void hold(ArenaBounds box) {
        held.add(box.describe());
    }

    @Override
    public void release(ArenaBounds box) {
        held.remove(box.describe());
    }

    @Override
    public long seed() {
        return seed;
    }

    @Override
    public LobbyPlacement.Blocks blocks() {
        return this;
    }

    @Override
    public boolean is(int x, int y, int z, String state) {
        return java.util.Objects.equals(world.get(GeneratedLobby.Blueprint.key(x, y, z)), state);
    }

    @Override
    public void set(int x, int y, int z, String state) {
        if (failing != null) {
            throw new IllegalStateException(failing);
        }
        if (writes >= failAfter) {
            throw new IllegalStateException("stopped");
        }
        writes++;
        if (state == null) {
            world.remove(GeneratedLobby.Blueprint.key(x, y, z));
        } else {
            world.put(GeneratedLobby.Blueprint.key(x, y, z), state);
        }
    }

    public String at(int x, int y, int z) {
        return world.get(GeneratedLobby.Blueprint.key(x, y, z));
    }

    @Override
    public void removeEntities(ArenaBounds box) {
        entityClears.add(box.describe());
        cushions.clear();
    }

    @Override
    public void spawn(LobbyPiece.Cushion cushion) {
        cushions.add(cushion);
    }
}
