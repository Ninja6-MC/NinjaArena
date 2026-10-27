package com.ninja6.ninjaarena.support;

import com.destroystokyo.paper.SkinParts.Mutable;
import io.papermc.paper.InternalAPIBridge;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.poi.PoiType.Occupancy;
import io.papermc.paper.persistence.PersistentDataKey;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.world.damagesource.CombatEntry;
import io.papermc.paper.world.damagesource.FallLocationType;
import java.io.IOException;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.attribute.Attributable;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageEffect;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageSource.Builder;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pose;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Minimal service bridge for non-empty inventory snapshot round trips in unit tests. */
public final class TestInternalBridge implements InternalAPIBridge {

    @Override
    public Biome constructLegacyCustomBiome() {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public CombatEntry createCombatEntry(LivingEntity arg0, DamageSource arg1, float arg2) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public CombatEntry createCombatEntry(DamageSource arg0, float arg1, FallLocationType arg2, float arg3) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public Predicate<CommandSourceStack> restricted(Predicate<CommandSourceStack> arg0, boolean arg1) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public ResolvableProfile defaultMannequinProfile() {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public Mutable allSkinParts() {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public Component defaultMannequinDescription() {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public <MODERN, LEGACY> GameRule<LEGACY> legacyGameRuleBridge(GameRule<MODERN> arg0, Function<LEGACY, MODERN> arg1, Function<MODERN, LEGACY> arg2, Class<LEGACY> arg3) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public Set<Pose> validMannequinPoses() {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public Occupancy createOccupancy(String arg0) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public Builder createDamageSourceBuilder(DamageType arg0) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public DamageEffect getDamageEffect(String arg0) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public String getTranslationKey(EntityType arg0) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public SpawnCategory getSpawnCategory(EntityType arg0) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public ItemStack deserializeItem(byte[] arg0) {
        return TestItemStack.fromBytes(arg0);
    }

    @Override
    public boolean hasDefaultEntityAttributes(NamespacedKey arg0) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public Attributable getDefaultEntityAttributes(NamespacedKey arg0) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public String getStatisticCriteriaKey(Statistic arg0) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public LifecycleEventManager<Plugin> createPluginLifecycleEventManager(JavaPlugin arg0, BooleanSupplier arg1) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public ItemStack createEmptyStack() {
        return new TestItemStack(Material.AIR, "empty", 0);
    }

    @Override
    public Component resolveWithContext(Component arg0, CommandSender arg1, Entity arg2, boolean arg3) throws IOException {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public ComponentFlattener componentFlattener() {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public <C> PersistentDataKey<C> createPersistentDataKey(Key arg0, PersistentDataType<?, C> arg1) {
        throw new UnsupportedOperationException("unused API bridge call");
    }

    @Override
    public EnchantmentOffer[] rollEnchantmentOffers(ItemStack arg0, int arg1, int arg2, int arg3) {
        throw new UnsupportedOperationException("unused API bridge call");
    }
}
