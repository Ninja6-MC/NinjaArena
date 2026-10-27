package com.ninja6.ninjaarena.support;

import io.papermc.paper.persistence.PersistentDataContainerView;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/** A headless item carrying string data tags, for code that recognises items by their tags. */
public final class TestTaggedItem extends ItemStack {
    private final Material material;
    private final Map<NamespacedKey, String> tags;
    private int amount;

    public TestTaggedItem(Material material, Map<NamespacedKey, String> tags, int amount) {
        this.material = material;
        this.tags = Map.copyOf(tags);
        this.amount = amount;
    }

    @Override
    public Material getType() {
        return material;
    }

    @Override
    public int getAmount() {
        return amount;
    }

    @Override
    public void setAmount(int amount) {
        this.amount = amount;
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public boolean isEmpty() {
        return amount == 0;
    }

    @Override
    public PersistentDataContainerView getPersistentDataContainer() {
        return (PersistentDataContainerView) Proxy.newProxyInstance(
                PersistentDataContainerView.class.getClassLoader(), new Class<?>[] {PersistentDataContainerView.class},
                (p, m, a) -> switch (m.getName()) {
                    case "get" -> tags.get((NamespacedKey) a[0]);
                    case "has" -> tags.containsKey((NamespacedKey) a[0]);
                    case "isEmpty" -> tags.isEmpty();
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Override
    public TestTaggedItem clone() {
        return new TestTaggedItem(material, tags, amount);
    }
}
