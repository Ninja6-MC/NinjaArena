package com.ninja6.ninjaarena.snapshot;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One snapshot file per player UUID in a single directory.
 *
 * <p>A save writes the whole snapshot to a temporary file in the same directory, forces it
 * to disk and then moves it over the target in one step, so a crash mid-write leaves
 * either the previous file or the new one, never a partial file. Leftover temporary files
 * from an interrupted save are removed when the store is opened.
 */
public final class SnapshotStore {

    static final String SUFFIX = ".yml";
    static final String TEMP_SUFFIX = ".tmp";

    /** The final step of a save. Swappable in tests to simulate a failing filesystem. */
    @FunctionalInterface
    interface Mover {
        void move(Path source, Path target) throws IOException;
    }

    private final Path directory;
    private final Mover mover;

    public SnapshotStore(Path directory) throws IOException {
        this(directory, SnapshotStore::atomicMove);
    }

    SnapshotStore(Path directory, Mover mover) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.mover = Objects.requireNonNull(mover, "mover");
        Files.createDirectories(directory);
        removeLeftoverTempFiles();
    }

    public Path directory() {
        return directory;
    }

    /**
     * Writes the snapshot, replacing any earlier one for the same player.
     *
     * @throws IOException if the snapshot is not on disk when this returns; any earlier
     *                     snapshot for the player is then left untouched
     */
    public synchronized void save(PlayerSnapshot snapshot) throws IOException {
        byte[] bytes = SnapshotCodec.encode(snapshot).getBytes(StandardCharsets.UTF_8);
        Path target = fileFor(snapshot.playerId());
        Path temp = Files.createTempFile(directory, snapshot.playerId() + "-", TEMP_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            mover.move(temp, target);
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
     * Reads the snapshot for a player.
     *
     * @return empty if the player has no snapshot
     * @throws IOException if a snapshot exists but cannot be read
     */
    public synchronized Optional<PlayerSnapshot> load(UUID playerId) throws IOException {
        Path file = fileFor(playerId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        PlayerSnapshot snapshot = SnapshotCodec.decode(Files.readString(file, StandardCharsets.UTF_8));
        if (!snapshot.playerId().equals(playerId)) {
            throw new SnapshotCodec.SnapshotFormatException(
                    file.getFileName() + " holds the snapshot of " + snapshot.playerId());
        }
        return Optional.of(snapshot);
    }

    public synchronized boolean exists(UUID playerId) {
        return Files.isRegularFile(fileFor(playerId));
    }

    /** The players that have a snapshot on disk, in no particular order. */
    public synchronized List<UUID> listPending() throws IOException {
        List<UUID> ids = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String name = file.getFileName().toString();
                try {
                    ids.add(UUID.fromString(name.substring(0, name.length() - SUFFIX.length())));
                } catch (IllegalArgumentException ignored) {
                    // Not a snapshot file; leave it alone.
                }
            }
        }
        return ids;
    }

    /**
     * Removes a player's snapshot.
     *
     * @return whether a snapshot was there to remove
     */
    public synchronized boolean delete(UUID playerId) throws IOException {
        return Files.deleteIfExists(fileFor(playerId));
    }

    Path fileFor(UUID playerId) {
        return directory.resolve(playerId + SUFFIX);
    }

    private void removeLeftoverTempFiles() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + TEMP_SUFFIX)) {
            for (Path file : files) {
                Files.deleteIfExists(file);
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
