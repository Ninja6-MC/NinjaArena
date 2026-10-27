package com.ninja6.ninjaarena.support;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType.InternalPotionData;

/** Only world fallback and base-potion data are needed by these unit tests. */
public final class TestServer {
    private TestServer() {
    }

    public static void install() {
        if (Bukkit.getServer() != null) {
            return;
        }
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (p, m, a) -> {
                    if (m.getName().equals("getSpawnLocation")) {
                        return new Location((World) p, 0.5, 64, 0.5);
                    }
                    if (m.getName().equals("getName")) {
                        return "world";
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        UnsafeValues unsafe = (UnsafeValues) Proxy.newProxyInstance(UnsafeValues.class.getClassLoader(),
                new Class<?>[] {UnsafeValues.class}, (p, m, a) -> {
                    if (m.getName().equals("getInternalPotionData")) {
                        if (!org.bukkit.NamespacedKey.minecraft("poison").equals(a[0])) {
                            throw new UnsupportedOperationException("Only poison potion data is provided");
                        }
                        return Proxy.newProxyInstance(InternalPotionData.class.getClassLoader(),
                                new Class<?>[] {InternalPotionData.class}, (potion, method, args) -> {
                                    if (method.getName().equals("getPotionEffects")) {
                                        return List.of(new PotionEffect(PotionEffectType.POISON, 100, 0));
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getWorld" -> null;
                    case "getWorlds" -> List.of(world);
                    case "getUnsafe" -> unsafe;
                    case "getLogger" -> Logger.getAnonymousLogger();
                    case "getName", "getVersion", "getBukkitVersion" -> "test";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        // setServer also prints build information, which is supplied only by a real server.
        try {
            var field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
