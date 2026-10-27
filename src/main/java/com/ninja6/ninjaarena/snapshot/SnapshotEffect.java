package com.ninja6.ninjaarena.snapshot;

import java.util.Objects;

/**
 * One active potion effect. The type is stored by its namespaced key
 * (for example {@code minecraft:speed}) so the model does not depend on the registry.
 */
public record SnapshotEffect(
        String type,
        int duration,
        int amplifier,
        boolean ambient,
        boolean particles,
        boolean icon) {

    public SnapshotEffect {
        Objects.requireNonNull(type, "type");
    }
}
