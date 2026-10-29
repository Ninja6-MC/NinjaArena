package com.ninja6.ninjaarena.kit;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Named item providers for plugin-defined kit items. Register on the server thread before a round starts. */
public final class KitItems {

    private final Map<String, Function<Player, ItemStack>> providers = new HashMap<>();
    private final Map<String, Integer> stackLimits = new HashMap<>();
    private final Function<Material, ItemStack> vanilla;

    public KitItems() {
        this(ItemStack::of);
    }

    KitItems(Function<Material, ItemStack> vanilla) {
        this.vanilla = Objects.requireNonNull(vanilla, "vanilla");
    }

    public void register(String id, Function<Player, ItemStack> provider) {
        register(id, 1, provider);
    }

    /** Declares a safe stack limit so reload validation need not call a player-dependent provider. */
    public void register(String id, int stackLimit, Function<Player, ItemStack> provider) {
        if (stackLimit < 1 || stackLimit > 99) {
            throw new IllegalArgumentException("stack limit must be from 1 to 99");
        }
        if (!validId(id)) {
            throw new IllegalArgumentException("item id must be namespaced: " + id);
        }
        Objects.requireNonNull(provider, "provider");
        if (providers.putIfAbsent(id, provider) != null) {
            throw new IllegalArgumentException("item provider already registered: " + id);
        }
        stackLimits.put(id, stackLimit);
    }

    /** Zero means an unregistered id. Legacy providers without metadata safely allow one item. */
    public int stackLimit(String id) {
        return stackLimits.getOrDefault(id, 0);
    }

    static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9._-]+:[a-z0-9/._-]+");
    }

    /** Resolves at application time, preserves provider metadata and isolates mutable stacks. */
    ItemStack create(KitDefinition.Entry entry, Player player) {
        ItemStack supplied;
        if (entry.id() != null) {
            var provider = providers.get(entry.id());
            if (provider == null) {
                throw new IllegalArgumentException("unknown kit item id: " + entry.id());
            }
            supplied = provider.apply(player);
        } else {
            supplied = vanilla.apply(entry.material());
        }
        if (supplied == null || supplied.isEmpty()) {
            throw new IllegalArgumentException("slot " + entry.slot() + " produced an empty item");
        }
        ItemStack copy = supplied.clone();
        if (entry.amount() > copy.getMaxStackSize()) {
            throw new IllegalArgumentException("slot " + entry.slot() + " amount exceeds the item's stack limit");
        }
        copy.setAmount(entry.amount());
        return copy;
    }
}
