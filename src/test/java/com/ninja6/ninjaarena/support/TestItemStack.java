package com.ninja6.ninjaarena.support;

import java.nio.charset.StandardCharsets;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Item data for headless kit and snapshot round trips, without a server item registry. */
public final class TestItemStack extends ItemStack {
    private final Material material;
    private final String marker;
    private int amount;

    public TestItemStack(Material material, String marker, int amount) {
        this.material = material;
        this.marker = marker;
        this.amount = amount;
    }

    public String marker() { return marker; }
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
    public TestItemStack clone() {
        return new TestItemStack(material, marker, amount);
    }

    @Override
    public byte[] serializeAsBytes() {
        return (material.name() + ":" + marker + ":" + amount).getBytes(StandardCharsets.UTF_8);
    }

    public static TestItemStack fromBytes(byte[] bytes) {
        String[] parts = new String(bytes, StandardCharsets.UTF_8).split(":");
        return new TestItemStack(Material.valueOf(parts[0]), parts[1], Integer.parseInt(parts[2]));
    }
}
