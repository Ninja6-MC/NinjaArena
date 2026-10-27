package com.ninja6.ninjaarena.arena;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Reads and writes {@code arenas.yml}. A save writes the whole file to a temporary file
 * beside it, forces it to disk and moves it over the old one in one step, so a crash
 * mid-write leaves either the previous file or the new one.
 */
public final class ArenaStore {

    static final String TEMP_SUFFIX = ".tmp";

    /** The final step of a save. Swappable in tests to simulate a failing filesystem. */
    @FunctionalInterface
    interface Mover {
        void move(Path source, Path target) throws IOException;
    }

    private final Path file;
    private final Mover mover;

    public ArenaStore(Path file) {
        this(file, ArenaStore::atomicMove);
    }

    ArenaStore(Path file, Mover mover) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        this.mover = Objects.requireNonNull(mover, "mover");
    }

    public Path file() {
        return file;
    }

    /**
     * Reads every arena. A missing file is an empty registry.
     *
     * @throws IOException if the file exists but cannot be read or parsed
     */
    public List<Arena> load() throws IOException {
        removeLeftoverTempFiles();
        if (!Files.exists(file)) {
            return List.of();
        }
        return ArenaCodec.decode(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Replaces the file with the given arenas.
     *
     * @throws IOException if the new file is not on disk when this returns; the previous
     *                     file is then left untouched
     */
    public synchronized void save(Collection<Arena> arenas) throws IOException {
        byte[] bytes = ArenaCodec.encode(arenas).getBytes(StandardCharsets.UTF_8);
        Path directory = file.getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, file.getFileName() + "-", TEMP_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            mover.move(temp, file);
            forceDirectory(directory);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /**
     * Flushes the directory entry so the rename survives a power loss. Windows cannot open a
     * directory as a channel, so the failure is ignored there.
     */
    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException unsupported) {
            // Not supported on this platform.
        }
    }

    /** Removes temporary files left by a save that was interrupted before its move. */
    private void removeLeftoverTempFiles() throws IOException {
        Path directory = file.getParent();
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory,
                file.getFileName() + "-*" + TEMP_SUFFIX)) {
            for (Path leftover : files) {
                Files.deleteIfExists(leftover);
            }
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Same directory, so this only happens on filesystems without rename support.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
