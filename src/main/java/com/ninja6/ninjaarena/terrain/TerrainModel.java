package com.ninja6.ninjaarena.terrain;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One team's generated side as an immutable grid of {@link BlockKind} cells, made by
 * {@link TerrainGenerator}. It holds no world, location or block data, so it can be built on
 * any thread, kept, compared and placed later.
 *
 * <p>Axes are those of {@link SideDimensions}: the spawn edge is {@code z = 0}
 * ({@link #spawnEdgeZ()}), the centre-facing edge is {@code z = length - 1}
 * ({@link #centreEdgeZ()}). The first {@link SideDimensions#spawnDepth()} rows are a flat
 * spawn zone whose top is {@link #spawnSurfaceY()}, and the last
 * {@link SideDimensions#centreDepth()} rows a flat ledge whose top is
 * {@link #centreSurfaceY()} where the side meets the centre.
 *
 * <p>Cells are one byte each, the kind's ordinal, in column order: index
 * {@code (z * width + x) * height + y}, so each column is contiguous.
 */
public final class TerrainModel {

    /** Bumped when the digest's byte layout changes, so old digests cannot match new ones. */
    static final int DIGEST_FORMAT = 1;

    private final long seed;
    private final int difficulty;
    private final SideDimensions dimensions;
    private final TerrainTheme theme;
    private final int spawnSurfaceY;
    private final int centreSurfaceY;
    private final byte[] cells;

    /** Takes ownership of {@code cells}; the generator must not touch the array afterwards. */
    TerrainModel(long seed, int difficulty, SideDimensions dimensions, TerrainTheme theme, int spawnSurfaceY,
            int centreSurfaceY, byte[] cells) {
        if (cells.length != dimensions.volume()) {
            throw new IllegalArgumentException("cells do not match the dimensions");
        }
        this.seed = seed;
        this.difficulty = difficulty;
        this.dimensions = dimensions;
        this.theme = theme;
        this.spawnSurfaceY = spawnSurfaceY;
        this.centreSurfaceY = centreSurfaceY;
        this.cells = cells;
    }

    public long seed() {
        return seed;
    }

    public int difficulty() {
        return difficulty;
    }

    public SideDimensions dimensions() {
        return dimensions;
    }

    public TerrainTheme theme() {
        return theme;
    }

    /** The row on the spawn edge: always {@code 0}. */
    public int spawnEdgeZ() {
        return 0;
    }

    /** The row on the centre-facing edge: {@code length - 1}. */
    public int centreEdgeZ() {
        return dimensions.length() - 1;
    }

    /** The {@code y} of the spawn zone's top blocks; players stand at {@code y + 1}. */
    public int spawnSurfaceY() {
        return spawnSurfaceY;
    }

    /** The {@code y} of the centre ledge's top blocks, where the centre joins the side. */
    public int centreSurfaceY() {
        return centreSurfaceY;
    }

    /** The kind at a cell; throws if the cell is outside the model. */
    public BlockKind at(int x, int y, int z) {
        return BlockKind.ofOrdinal(cells[index(x, y, z)]);
    }

    /**
     * The highest terrain cell in a column, ignoring floating platforms: the top of the
     * ground, which may roof an overhang. Ignores lava and collapsing platform rims.
     * {@code -1} if the column has no solid ground.
     */
    public int terrainTopY(int x, int z) {
        int base = index(x, 0, z);
        for (int y = dimensions.height() - 1; y >= 0; y--) {
            byte kind = cells[base + y];
            if (BlockKind.ofOrdinal(kind).solid() && kind != BlockKind.PLATFORM.ordinal()
                    && kind != BlockKind.CRUMBLE_PLATFORM.ordinal()) {
                return y;
            }
        }
        return -1;
    }

    /** How many cells hold the given kind. */
    public int count(BlockKind kind) {
        int count = 0;
        byte ordinal = (byte) kind.ordinal();
        for (byte cell : cells) {
            if (cell == ordinal) {
                count++;
            }
        }
        return count;
    }

    /** Memory held by the cells, in bytes. */
    public int cellBytes() {
        return cells.length;
    }

    /**
     * A SHA-256 hex digest of everything that defines the model: format, seed, difficulty,
     * dimensions, theme, flat-zone heights and every cell. Equal digests mean equal models.
     */
    public String digest() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            ByteBuffer header = ByteBuffer.allocate(40);
            header.putInt(DIGEST_FORMAT).putLong(seed).putInt(difficulty).putInt(dimensions.width())
                    .putInt(dimensions.length()).putInt(dimensions.height()).putInt(theme.ordinal())
                    .putInt(spawnSurfaceY).putInt(centreSurfaceY);
            sha.update(header.array());
            sha.update(cells);
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    private int index(int x, int y, int z) {
        if (x < 0 || x >= dimensions.width() || y < 0 || y >= dimensions.height() || z < 0
                || z >= dimensions.length()) {
            throw new IndexOutOfBoundsException("cell " + x + ", " + y + ", " + z + " is outside the model");
        }
        return (z * dimensions.width() + x) * dimensions.height() + y;
    }
}
