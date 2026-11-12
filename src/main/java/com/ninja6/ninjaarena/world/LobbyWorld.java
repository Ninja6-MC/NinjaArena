package com.ninja6.ninjaarena.world;

import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.World;

/**
 * The void world the lobby stands in, beside the arena world. Unlike {@link ArenaWorld} it is
 * saved normally and never retired: what is built in it stays, and it is loaded for as long
 * as the plugin is enabled.
 *
 * <p>It has the arena world's rules ({@link ArenaWorld#configureRules}): inventories are
 * kept, time stands at noon, the weather stays clear and no mobs spawn. Environmental block
 * changes are blocked by {@code ArenaProtectionListener}, with no edit-mode exception.
 */
public final class LobbyWorld {

    public static final String NAME = "ninjaarena_lobby";

    private final World world;

    LobbyWorld(World world) {
        this.world = Objects.requireNonNull(world, "world");
        configure(world);
    }

    /**
     * Loads the lobby world, creating it if it does not exist yet. The rules are applied on
     * every load.
     */
    public static LobbyWorld load(Logger logger) {
        LobbyWorld lobby = new LobbyWorld(ArenaWorld.openVoid(NAME));
        logger.info("Lobby world " + NAME + " loaded with saving on.");
        return lobby;
    }

    static void configure(World world) {
        world.setAutoSave(true);
        ArenaWorld.configureRules(world);
    }

    public World world() {
        return world;
    }
}
