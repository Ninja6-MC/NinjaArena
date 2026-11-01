package com.ninja6.ninjaarena.arena;

import com.ninja6.ninjaarena.world.ArenaExtent;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;

/**
 * Arena copies as vanilla structure files, one directory per arena under
 * {@code plugins/NinjaArena/arena-copies}. An arena is split into chunk columns
 * ({@link ArenaBounds#chunkColumns()}) so no single structure holds more than a 16 by 16
 * column of blocks, and each file is named after the corner it was taken from, so a copy is
 * always put back where it came from.
 *
 * <p>A copy holds blocks, air included, and block entities such as chest contents; it does
 * not hold entities. A restore removes every entity inside the copied area except players
 * and decorations (armour stands, item frames, paintings, display entities and markers),
 * which are left as they are.
 *
 * <p>A new copy is written to a separate directory and moved into place once complete, so
 * a failed save leaves the previous copy.
 */
public final class StructureArenaCopies implements ArenaCopies {

    private static final String EXTENSION = ".nbt";
    private static final Pattern FILE = Pattern.compile("(-?\\d+)_(-?\\d+)_(-?\\d+)\\.nbt");

    private final Path dir;
    private final Supplier<World> world;
    private final StructureManager structures;
    private Consumer<World> restored = ignored -> { };

    public StructureArenaCopies(Path dir, World world, StructureManager structures) {
        this(dir, () -> world, structures);
    }

    public StructureArenaCopies(Path dir, Supplier<World> world, StructureManager structures) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.world = Objects.requireNonNull(world, "world");
        this.structures = Objects.requireNonNull(structures, "structures");
    }

    /** Runs before a restored copy can be used by another match or edit. */
    public StructureArenaCopies afterRestore(Consumer<World> restored) {
        this.restored = Objects.requireNonNull(restored, "restored");
        return this;
    }

    @Override
    public void save(String name, ArenaBounds bounds) throws IOException {
        if (!ArenaExtent.contains(bounds)) {
            throw new IOException("Arena bounds exceed the supported arena-world extent");
        }
        World world = this.world.get();
        Path target = dir.resolve(name);
        Path fresh = dir.resolve(name + ".new");
        deleteTree(fresh);
        Files.createDirectories(fresh);
        for (ArenaBounds column : bounds.chunkColumns()) {
            Structure structure = structures.createStructure();
            structure.fill(new Location(world, column.minX(), column.minY(), column.minZ()),
                    new BlockVector(column.maxX() - column.minX() + 1, column.maxY() - column.minY() + 1,
                            column.maxZ() - column.minZ() + 1),
                    false);
            structures.saveStructure(fresh.resolve(column.minX() + "_" + column.minY() + "_" + column.minZ()
                    + EXTENSION).toFile(), structure);
        }
        deleteTree(target);
        Files.move(fresh, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void restore(String name) throws IOException {
        World world = this.world.get();
        Path source = dir.resolve(name);
        if (!Files.isDirectory(source)) {
            throw new IOException("there is no saved copy of " + name);
        }
        List<Path> files = new ArrayList<>();
        List<Runnable> cleanup = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source, "*" + EXTENSION)) {
            stream.forEach(files::add);
        }
        for (Path file : files) {
            Matcher origin = FILE.matcher(file.getFileName().toString());
            if (!origin.matches()) {
                throw new IOException("unexpected file " + file + " in the copy of " + name);
            }
            int x = Integer.parseInt(origin.group(1));
            int y = Integer.parseInt(origin.group(2));
            int z = Integer.parseInt(origin.group(3));
            Structure structure = structures.loadStructure(file.toFile());
            BlockVector size = structure.getSize();
            if (size.getBlockX() < 1 || size.getBlockX() > 16 || size.getBlockZ() < 1 || size.getBlockZ() > 16
                    || size.getBlockY() < 1
                    || !ArenaExtent.contains(x, z)
                    || !ArenaExtent.contains((long) x + size.getBlockX() - 1, (long) z + size.getBlockZ() - 1)
                    || y < world.getMinHeight() || (long) y + size.getBlockY() > world.getMaxHeight()) {
                throw new IOException("Saved arena column exceeds the supported world extent: " + file.getFileName());
            }
            // Each file is one chunk column. Entities load separately from blocks, after the
            // chunk does; Chunk#getEntities loads them first, so what the last save wrote there
            // (mobs, items, arrows left mid-match) is seen and cleared.
            Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
            clearEntities(chunk, new BoundingBox(x, y, z, x + size.getBlockX(), y + size.getBlockY(),
                    z + size.getBlockZ()));
            structure.place(new Location(world, x, y, z), false, StructureRotation.NONE, Mirror.NONE, 0, 1f,
                    new Random());
            // A later neighboring column can also drop an item into this one.
            BoundingBox box = new BoundingBox(x, y, z, x + size.getBlockX(), y + size.getBlockY(),
                    z + size.getBlockZ());
            cleanup.add(() -> clearEntities(chunk, box));
        }
        cleanup.forEach(Runnable::run);
        restored.accept(world);
    }

    @Override
    public boolean exists(String name) {
        return Files.isDirectory(dir.resolve(name));
    }

    @Override
    public void delete(String name) throws IOException {
        deleteTree(dir.resolve(name));
        deleteTree(dir.resolve(name + ".new"));
    }

    private static void clearEntities(Chunk chunk, BoundingBox box) {
        for (Entity entity : chunk.getEntities()) {
            if (!box.contains(entity.getLocation().toVector())) {
                continue;
            }
            if (!(entity instanceof Player || entity instanceof ArmorStand || entity instanceof Hanging
                    || entity instanceof Display || entity instanceof Marker)) {
                entity.remove();
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }
}
