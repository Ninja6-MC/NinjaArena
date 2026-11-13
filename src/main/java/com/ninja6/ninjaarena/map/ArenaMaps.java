package com.ninja6.ninjaarena.map;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.arena.MapSource;
import java.util.Objects;
import java.util.Optional;

/**
 * Every map by name: generated maps from {@link GeneratedMaps}, every other name from the
 * hand-built {@link ArenaRegistry}. A generated map's name cannot be a hand-built one, so
 * the name alone says which to ask. Server thread only.
 */
public final class ArenaMaps implements MapSource {

    private final ArenaRegistry handBuilt;
    private final GeneratedMaps generated;

    public ArenaMaps(ArenaRegistry handBuilt, GeneratedMaps generated) {
        this.handBuilt = Objects.requireNonNull(handBuilt, "handBuilt");
        this.generated = Objects.requireNonNull(generated, "generated");
    }

    private static boolean isGenerated(String name) {
        return Arena.isGeneratedName(Arena.normalizeName(name));
    }

    @Override
    public Optional<Arena> get(String name) {
        return isGenerated(name) ? generated.get(name) : handBuilt.get(name);
    }

    @Override
    public boolean isAvailable(String name) {
        return isGenerated(name) ? generated.isAvailable(name) : handBuilt.isAvailable(name);
    }

    @Override
    public boolean isHeld(String name) {
        return isGenerated(name) ? generated.isHeld(name) : handBuilt.isHeld(name);
    }

    @Override
    public void hold(String name) throws ArenaException {
        if (isGenerated(name)) {
            generated.hold(name);
        } else {
            handBuilt.hold(name);
        }
    }

    @Override
    public void reset(String name) throws ArenaException {
        if (isGenerated(name)) {
            generated.reset(name);
        } else {
            handBuilt.reset(name);
        }
    }

    @Override
    public void release(String name) {
        if (isGenerated(name)) {
            generated.release(name);
        } else {
            handBuilt.release(name);
        }
    }
}
