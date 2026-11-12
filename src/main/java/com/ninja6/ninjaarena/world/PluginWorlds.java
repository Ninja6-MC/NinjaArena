package com.ninja6.ninjaarena.world;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * The one check for the two worlds NinjaArena owns: the disposable arena world
 * ({@value ArenaWorld#NAME}) and the saved lobby world ({@value LobbyWorld#NAME}).
 *
 * <p>Worlds are told apart by name, never by world object: the arena world is retired and
 * reopened as a new object, and a name check never loads a world. Every method accepts
 * {@code null} and answers {@code false} for it.
 */
public final class PluginWorlds {

    private PluginWorlds() { }

    public static boolean isArena(String name) {
        return ArenaWorld.NAME.equals(name);
    }

    public static boolean isArena(World world) {
        return world != null && isArena(world.getName());
    }

    public static boolean isArena(Location location) {
        return location != null && isArena(location.getWorld());
    }

    public static boolean isLobby(String name) {
        return LobbyWorld.NAME.equals(name);
    }

    public static boolean isLobby(World world) {
        return world != null && isLobby(world.getName());
    }

    public static boolean isLobby(Location location) {
        return location != null && isLobby(location.getWorld());
    }

    /** Either world: no portal, snapshot or restore landing belongs in it. */
    public static boolean isPluginWorld(String name) {
        return isArena(name) || isLobby(name);
    }

    public static boolean isPluginWorld(World world) {
        return world != null && isPluginWorld(world.getName());
    }

    public static boolean isPluginWorld(Location location) {
        return location != null && isPluginWorld(location.getWorld());
    }
}
