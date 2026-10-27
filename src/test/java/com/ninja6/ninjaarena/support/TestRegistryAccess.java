package com.ninja6.ninjaarena.support;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import org.bukkit.Color;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.MenuType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;

/** Minimal registry provider for API constants used by listener and restore tests. */
public final class TestRegistryAccess implements RegistryAccess {

    @Override
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        return registry(type == Attribute.class ? "attribute" : type.getSimpleName());
    }

    @Override
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
        return registry(key.key().value());
    }

    @SuppressWarnings("unchecked")
    private <T extends Keyed> Registry<T> registry(String name) {
        Map<String, Keyed> entries = new HashMap<>();
        return (Registry<T>) Proxy.newProxyInstance(Registry.class.getClassLoader(), new Class<?>[] {Registry.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("get") || method.getName().equals("getOrThrow")) {
                        String value = ((Key) args[0]).value();
                        if (name.equals("item")) {
                            if (Set.of("water", "fire", "nether_portal").contains(value)) {
                                return null;
                            }
                            return Proxy.newProxyInstance(ItemType.Typed.class.getClassLoader(),
                                    new Class<?>[] {ItemType.Typed.class}, (p, m, a) -> switch (m.getName()) {
                                        case "typed" -> p;
                                        case "getKey" -> NamespacedKey.minecraft(value);
                                        default -> throw new UnsupportedOperationException(m.getName());
                                    });
                        }
                        if (name.equals("menu")) {
                            return Proxy.newProxyInstance(MenuType.Typed.class.getClassLoader(),
                                    new Class<?>[] {MenuType.Typed.class}, (p, m, a) -> switch (m.getName()) {
                                        case "typed" -> p;
                                        case "getInventoryViewClass" -> InventoryView.class;
                                        case "getKey" -> NamespacedKey.minecraft(value);
                                        default -> throw new UnsupportedOperationException(m.getName());
                                    });
                        }
                        return entries.computeIfAbsent(value, key -> {
                            if (name.equals("mob_effect")) {
                                return new Effect(key);
                            }
                            if (name.equals("attribute")) {
                                return (Attribute) Proxy.newProxyInstance(Attribute.class.getClassLoader(),
                                        new Class<?>[] {Attribute.class}, (p, m, a) -> {
                                            throw new UnsupportedOperationException(m.getName());
                                        });
                            }
                            throw new UnsupportedOperationException(name + ":" + key);
                        });
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class Effect extends PotionEffectType {
        private final String name;

        Effect(String name) {
            this.name = name;
        }

        @Override
        public PotionEffectTypeCategory getCategory() {
            return switch (name) {
                case "poison", "instant_damage", "wither", "slowness" -> PotionEffectTypeCategory.HARMFUL;
                case "speed", "instant_health" -> PotionEffectTypeCategory.BENEFICIAL;
                default -> PotionEffectTypeCategory.NEUTRAL;
            };
        }

        @Override
        public NamespacedKey getKey() {
            return NamespacedKey.minecraft(name);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getId() {
            return 0;
        }

        @Override
        public boolean isInstant() {
            return name.startsWith("instant_");
        }

        @Override
        public Color getColor() {
            return Color.BLACK;
        }

        @Override
        public double getDurationModifier() {
            return 1;
        }

        @Override
        public String getTranslationKey() {
            return "effect.minecraft." + name;
        }

        @Override
        public String translationKey() {
            return getTranslationKey();
        }

        @Override
        public PotionEffect createEffect(int duration, int amplifier) {
            return new PotionEffect(this, duration, amplifier);
        }

        @Override
        public Map<Attribute, AttributeModifier> getEffectAttributes() {
            return Map.of();
        }

        @Override
        public double getAttributeModifierAmount(Attribute attribute, int amplifier) {
            return 0;
        }

        @Override
        public Category getEffectCategory() {
            return Category.valueOf(getCategory().name());
        }
    }
}
