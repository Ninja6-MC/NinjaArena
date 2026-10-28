package com.ninja6.ninjaarena.support;

import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;

/** A game rule known only by its key, so {@code GameRules} constants load in unit tests. */
public final class TestGameRule<T> extends GameRule<T> {

    private final NamespacedKey key;

    public TestGameRule(NamespacedKey key) {
        this.key = key;
    }

    @Override
    public NamespacedKey getKey() {
        return key;
    }

    @Override
    public String getName() {
        return key.getKey();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<T> getType() {
        return (Class<T>) Object.class;
    }

    @Override
    public T getDefaultValue() {
        return null;
    }

    @Override
    public String translationKey() {
        return "gamerule." + key.getKey();
    }
}
