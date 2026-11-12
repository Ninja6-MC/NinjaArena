package com.ninja6.ninjaarena.lobby;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;

/**
 * Places the bundled obsidian lobby ({@link LobbyLayout#STRUCTURE}) in the lobby world.
 *
 * <p>The build is placed once: the world records that it has it, and every later start, like
 * the arena world's retirements, leaves the lobby world as it is, with whatever admins
 * changed in edit mode. {@link #reset} is the only way the bundled build comes back.
 */
public final class LobbyBuild {

    /** What placing the build does to the world. */
    public interface Site {

        /** Whether the bundled build was placed in this world before. */
        boolean placed();

        /** Records that the bundled build is in this world. */
        void markPlaced();

        /** Turns every block in the box to air and removes every entity in it except players. */
        void clear(ArenaBounds box);

        /** Places the bundled structure with its lowest north-west corner at the given block. */
        void place(int x, int y, int z) throws IOException;
    }

    private final LobbyLayout layout;
    private final Site site;
    private final Logger logger;

    public LobbyBuild(LobbyLayout layout, Site site, Logger logger) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.site = Objects.requireNonNull(site, "site");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Places the bundled build if this world has never had it, as when the lobby world has
     * just been created. A failure is logged and tried again on the next start.
     *
     * @return whether the build was placed now
     */
    public boolean placeIfNew() {
        if (site.placed()) {
            return false;
        }
        try {
            place();
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "Could not place the bundled lobby build; the lobby world stays empty and"
                    + " the next start tries again.", e);
            return false;
        }
        logger.info("Placed the bundled lobby build at " + layout.build().describe() + ".");
        return true;
    }

    /** Clears the lobby bounds and places the bundled build again, dropping every admin change. */
    public void reset() throws IOException {
        site.clear(layout.bounds());
        place();
        logger.info("Reset the lobby build at " + layout.build().describe() + ".");
    }

    private void place() throws IOException {
        ArenaBounds build = layout.build();
        site.place(build.minX(), build.minY(), build.minZ());
        site.markPlaced();
    }

    /** The lobby world itself; the bundled structure is read from the plugin JAR. */
    public static Site site(World world, LobbyLayout layout) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(layout, "layout");
        NamespacedKey key = new NamespacedKey("ninjaarena", "lobby_build");
        return new Site() {
            @Override
            public boolean placed() {
                return world.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
            }

            @Override
            public void markPlaced() {
                world.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            }

            @Override
            public void clear(ArenaBounds box) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        for (int y = box.minY(); y <= box.maxY(); y++) {
                            var block = world.getBlockAt(x, y, z);
                            if (!block.getType().isAir()) {
                                block.setType(Material.AIR, false);
                            }
                        }
                    }
                }
                BoundingBox area = new BoundingBox(box.minX(), box.minY(), box.minZ(), box.maxX() + 1,
                        box.maxY() + 1, box.maxZ() + 1);
                for (Entity entity : world.getNearbyEntities(area)) {
                    if (!(entity instanceof Player)) {
                        entity.remove();
                    }
                }
            }

            @Override
            public void place(int x, int y, int z) throws IOException {
                Structure structure;
                try (InputStream in = LobbyBuild.class.getClassLoader().getResourceAsStream(LobbyLayout.STRUCTURE)) {
                    if (in == null) {
                        throw new IOException(LobbyLayout.STRUCTURE + " is missing from the plugin JAR");
                    }
                    structure = Bukkit.getStructureManager().loadStructure(in);
                }
                ArenaBounds build = layout.build();
                BlockVector size = structure.getSize();
                if (size.getBlockX() != build.maxX() - build.minX() + 1
                        || size.getBlockY() != build.maxY() - build.minY() + 1
                        || size.getBlockZ() != build.maxZ() - build.minZ() + 1) {
                    throw new IOException("the bundled lobby structure is " + size + ", not the size in "
                            + LobbyLayout.SPEC);
                }
                structure.place(new Location(world, x, y, z), false, StructureRotation.NONE, Mirror.NONE, 0, 1f,
                        new Random(0));
            }
        };
    }
}
