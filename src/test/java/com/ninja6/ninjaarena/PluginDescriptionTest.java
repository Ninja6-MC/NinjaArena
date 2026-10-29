package com.ninja6.ninjaarena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
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
    void commandIsTheLongNameWithTheShortAlias() throws Exception {
        Map<String, Object> command = description().getCommands().get("ninjaarena");
        assertNotNull(command, "ninjaarena is not declared");
        assertEquals(List.of("narena"), command.get("aliases"));
        assertEquals("ninjaarena.play;ninjaarena.host;ninjaarena.admin", command.get("permission"));
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
        assertEquals(Map.of(), nodes.get("ninjaarena.play").getChildren());
        assertEquals(Map.of("ninjaarena.play", true), nodes.get("ninjaarena.host").getChildren());
        assertEquals(Map.of("ninjaarena.host", true), nodes.get("ninjaarena.admin").getChildren());
    }

    @Test
    void everyTierHasAnExplicitChildrenBlockAndAdminReachesPlayThroughHost() throws Exception {
        try (InputStream stream = pluginYml()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String tier : List.of("play", "host", "admin")) {
                assertTrue(yaml.isConfigurationSection("permissions.ninjaarena." + tier + ".children"), tier);
            }
            assertTrue(yaml.getBoolean("permissions.ninjaarena.admin.children.ninjaarena.host"));
            assertTrue(yaml.getBoolean("permissions.ninjaarena.host.children.ninjaarena.play"));
            assertTrue(yaml.getConfigurationSection("permissions.ninjaarena.play.children").getKeys(false).isEmpty());
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
        for (String granted : List.of("ninjaarena.play", "ninjaarena.host", "ninjaarena.admin", "none")) {
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
