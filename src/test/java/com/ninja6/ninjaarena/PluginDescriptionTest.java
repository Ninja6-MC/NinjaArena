package com.ninja6.ninjaarena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PluginDescriptionTest {

    private static InputStream pluginYml() {
        InputStream stream = PluginDescriptionTest.class.getClassLoader().getResourceAsStream("plugin.yml");
        assertNotNull(stream, "plugin.yml is not on the classpath");
        return stream;
    }

    private static PluginDescriptionFile description() throws IOException, InvalidDescriptionException {
        try (InputStream stream = pluginYml()) {
            return new PluginDescriptionFile(stream);
        }
    }

    @Test
    void mainPointsAtThePluginClass() throws Exception {
        PluginDescriptionFile description = description();
        assertEquals("NinjaArena", description.getName());
        assertEquals(NinjaArenaPlugin.class.getName(), description.getMain());
        assertEquals("26.3", description.getAPIVersion());
    }

    @Test
    void versionIsExpandedByTheBuild() throws Exception {
        assertFalse(description().getVersion().contains("${"), "plugin.yml version was not expanded");
    }

    /**
     * Runs only when the release candidate points the suite at its downloaded JAR. Without
     * this the suite could pass on the compiled classes and say nothing about the JAR.
     */
    @Test
    void releaseRunLoadsTheCandidateJarAndItsVersion() throws Exception {
        String releaseJar = System.getProperty("ninjaarena.releaseJar");
        Assumptions.assumeTrue(releaseJar != null, "not a release candidate run");
        Path expected = Path.of(releaseJar).toRealPath();
        Path pluginClass = Path.of(NinjaArenaPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toRealPath();
        assertEquals(expected, pluginClass, "plugin classes did not load from the release JAR");
        URL descriptor = PluginDescriptionTest.class.getClassLoader().getResource("plugin.yml");
        assertNotNull(descriptor, "plugin.yml is not on the classpath");
        assertEquals("jar", descriptor.getProtocol(), "plugin.yml did not load from a JAR");
        JarURLConnection connection = (JarURLConnection) descriptor.openConnection();
        assertEquals("plugin.yml", connection.getEntryName());
        assertEquals(expected, Path.of(connection.getJarFileURL().toURI()).toRealPath(),
                "plugin.yml did not load from the release JAR");
        assertEquals(System.getProperty("ninjaarena.releaseVersion"), description().getVersion());
    }

    @Test
    void foliaSupportIsDeclaredFalse() throws IOException {
        try (InputStream stream = pluginYml()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertTrue(yaml.isBoolean("folia-supported"), "folia-supported must be declared");
            assertFalse(yaml.getBoolean("folia-supported"));
        }
    }

    @Test
    void integrationsAreOptional() throws Exception {
        assertEquals(List.of("PlaceholderAPI", "Vault", "Geyser-Spigot", "floodgate"), description().getSoftDepend());
        assertFalse(description().getDepend().contains("PlaceholderAPI"));
        assertFalse(description().getDepend().contains("Vault"));
        assertFalse(description().getDepend().contains("Geyser-Spigot"));
        assertFalse(description().getDepend().contains("floodgate"));
    }

    @Test
    void commandIsTheLongNameWithTheShortAlias() throws Exception {
        Map<String, Object> command = description().getCommands().get("ninjaarena");
        assertNotNull(command, "ninjaarena is not declared");
        assertEquals(List.of("narena"), command.get("aliases"));
        assertEquals("ninjaarena.play;ninjaarena.host;ninjaarena.admin;ninjaarena.admin.lobby;ninjaarena.spectate;"
                + "ninjaarena.leaderboard;ninjaarena.event.manage",
                command.get("permission"));
    }

    @Test
    void permissionTiersInheritDownward() throws Exception {
        Map<String, Permission> nodes = new HashMap<>();
        for (Permission permission : description().getPermissions()) {
            nodes.put(permission.getName(), permission);
        }
        assertEquals(PermissionDefault.TRUE, nodes.get("ninjaarena.play").getDefault());
        assertEquals(PermissionDefault.OP, nodes.get("ninjaarena.host").getDefault());
        assertEquals(PermissionDefault.OP, nodes.get("ninjaarena.admin").getDefault());
        assertEquals(PermissionDefault.TRUE, nodes.get("ninjaarena.spectate").getDefault());
        assertEquals(Map.of(), nodes.get("ninjaarena.spectate").getChildren());
        assertEquals(PermissionDefault.TRUE, nodes.get("ninjaarena.leaderboard").getDefault());
        assertEquals(Map.of(), nodes.get("ninjaarena.leaderboard").getChildren());
        assertEquals(PermissionDefault.TRUE, nodes.get("ninjaarena.rewards.payout").getDefault());
        assertEquals(Map.of(), nodes.get("ninjaarena.rewards.payout").getChildren());
        assertEquals(PermissionDefault.TRUE, nodes.get("ninjaarena.shop.use").getDefault());
        assertEquals(Map.of(), nodes.get("ninjaarena.shop.use").getChildren());
        assertEquals(Map.of("ninjaarena.spectate", true, "ninjaarena.leaderboard", true,
                "ninjaarena.rewards.payout", true, "ninjaarena.room.create", true, "ninjaarena.shop.use", true),
                nodes.get("ninjaarena.play").getChildren());
        // Servers where nobody has op can open rooms: everyone has it, through play as well.
        assertEquals(PermissionDefault.TRUE, nodes.get("ninjaarena.room.create").getDefault());
        assertEquals(Map.of(), nodes.get("ninjaarena.room.create").getChildren());
        assertEquals(Map.of("ninjaarena.play", true), nodes.get("ninjaarena.host").getChildren());
        assertEquals(PermissionDefault.OP, nodes.get("ninjaarena.event.manage").getDefault());
        assertEquals(Map.of("ninjaarena.host", true, "ninjaarena.event.manage", true, "ninjaarena.admin.lobby", true,
                "ninjaarena.map.change", true),
                nodes.get("ninjaarena.admin").getChildren());
        assertEquals(PermissionDefault.OP, nodes.get("ninjaarena.map.change").getDefault());
        assertEquals(Map.of(), nodes.get("ninjaarena.map.change").getChildren());
        assertEquals(PermissionDefault.OP, nodes.get("ninjaarena.admin.lobby").getDefault());
        assertEquals(Map.of(), nodes.get("ninjaarena.admin.lobby").getChildren());
    }

    @Test
    void anAdminGrantGivesANonOperatorTheEffectiveMapPermission() throws Exception {
        Map<String, Permission> nodes = new HashMap<>();
        description().getPermissions().forEach(permission -> nodes.put(permission.getName(), permission));
        org.bukkit.plugin.PluginManager manager = (org.bukkit.plugin.PluginManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {org.bukkit.plugin.PluginManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPermission" -> nodes.get(args[0]);
                    case "getDefaultPermissions" -> java.util.Set.of();
                    case "subscribeToPermission", "unsubscribeFromPermission", "subscribeToDefaultPerms",
                            "unsubscribeFromDefaultPerms" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        org.bukkit.Server server = (org.bukkit.Server) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {org.bukkit.Server.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPluginManager")) {
                        return manager;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isEnabled")) {
                        return true;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var field = org.bukkit.Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        Object previous = field.get(null);
        try {
            field.set(null, server);
            var admin = new org.bukkit.permissions.PermissibleBase(null);
            assertFalse(admin.isOp());
            assertFalse(admin.hasPermission("ninjaarena.map.change"));
            var grant = admin.addAttachment(plugin, "ninjaarena.admin", true);
            assertTrue(admin.hasPermission("ninjaarena.map.change"));
            admin.removeAttachment(grant);
            assertFalse(admin.hasPermission("ninjaarena.map.change"));
        } finally {
            field.set(null, previous);
        }
    }

    @Test
    void everyTierHasAnExplicitChildrenBlockAndAdminReachesPlayThroughHost() throws Exception {
        try (InputStream stream = pluginYml()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String tier : List.of("shop.use", "spectate", "leaderboard", "rewards.payout", "room.create",
                    "event.manage", "map.change", "play", "host", "admin", "admin.lobby")) {
                assertTrue(yaml.isConfigurationSection("permissions.ninjaarena." + tier + ".children"), tier);
            }
            assertTrue(yaml.getBoolean("permissions.ninjaarena.admin.children.ninjaarena.host"));
            assertTrue(yaml.getBoolean("permissions.ninjaarena.host.children.ninjaarena.play"));
            assertTrue(yaml.getBoolean("permissions.ninjaarena.play.children.ninjaarena.spectate"));
            assertTrue(yaml.getConfigurationSection("permissions.ninjaarena.spectate.children").getKeys(false).isEmpty());
            assertEquals(List.of("ninjaarena"), description().getCommands().keySet().stream().toList());
        }
    }

    @Test
    void registeredRootAcceptsAnyTierAndRefusesSendersWithoutOne() throws Exception {
        Command root = new Command("ninjaarena") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        root.setPermission((String) description().getCommands().get("ninjaarena").get("permission"));
        for (String granted : List.of("ninjaarena.play", "ninjaarena.host", "ninjaarena.admin",
                "ninjaarena.admin.lobby", "ninjaarena.spectate", "ninjaarena.leaderboard", "none")) {
            CommandSender sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                    new Class<?>[] {CommandSender.class}, (proxy, method, args) -> {
                        if (method.getName().equals("hasPermission")) {
                            return granted.equals(args[0]);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            assertEquals(!granted.equals("none"), root.testPermissionSilent(sender), granted);
        }
    }
}
