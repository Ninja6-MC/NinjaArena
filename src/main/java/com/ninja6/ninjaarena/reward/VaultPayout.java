package com.ninja6.ninjaarena.reward;

import java.lang.reflect.Method;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;

/** The economy service is resolved only while Vault is enabled. */
final class VaultPayout {

    private static final String ECONOMY = "net.milkbowl.vault.economy.Economy";

    private record Provider(Class<?> service, Object instance) {
    }

    private VaultPayout() {
    }

    static boolean available(Server server) {
        return provider(server) != null;
    }

    static boolean deposit(Server server, OfflinePlayer player, long cents) {
        Provider provider = provider(server);
        return provider != null && deposit(provider.service(), provider.instance(), player, cents);
    }

    private static Provider provider(Server server) {
        if (!server.getPluginManager().isPluginEnabled("Vault")) {
            return null;
        }
        var services = server.getServicesManager();
        for (Class<?> service : services.getKnownServices()) {
            if (service.getName().equals(ECONOMY)) {
                Object instance = services.load(service);
                if (instance != null) {
                    return new Provider(service, instance);
                }
            }
        }
        return null;
    }

    static boolean deposit(Class<?> service, Object provider, OfflinePlayer player, long cents) {
        if (provider == null || cents <= 0) {
            return false;
        }
        try {
            Method deposit = service.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            Object response = deposit.invoke(provider, player, cents / 100.0);
            return response != null
                    && Boolean.TRUE.equals(deposit.getReturnType().getMethod("transactionSuccess").invoke(response));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
