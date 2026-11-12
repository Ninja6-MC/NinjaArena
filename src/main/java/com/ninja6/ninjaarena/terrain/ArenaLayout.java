package com.ninja6.ninjaarena.terrain;

import java.util.Objects;

/**
 * Immutable full-map view of one side and a flat centre, for two or four teams.
 *
 * <p>Teams are numbered from 1. Team 1 is north of the centre, facing positive z. With
 * two teams, team 2 is turned 180 degrees, not reflected: this preserves left and right
 * for each player facing the centre. With four teams, teams 2, 3 and 4 are east, south
 * and west, successive clockwise quarter-turns in the x/z plane. Height never changes.
 *
 * <p>The map's bounding box starts at (0, 0, 0). Unoccupied corners and all coordinates
 * outside the map contain air. Only the supplied immutable side is retained; neither
 * team copies nor centre cells are allocated. No world or thread access is needed.
 */
public final class ArenaLayout {

    /** An integer block coordinate, in the frame specified by the helper returning it. */
    public record Position(int x, int y, int z) { }

    /** Axis-aligned block bounds: minima inclusive, maxima exclusive. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(int x, int y, int z) {
            return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
        }
    }

    /** Unit horizontal direction from a team's spawn towards the centre. */
    public record Facing(int dx, int dz) { }

    private final TerrainModel side;
    private final int teamCount;
    private final int width;
    private final int length;
    private final int centreX;

    public ArenaLayout(TerrainModel side, int teamCount) {
        if (teamCount != 2 && teamCount != 4) {
            throw new IllegalArgumentException("team count must be 2 or 4");
        }
        this.side = Objects.requireNonNull(side, "side");
        this.teamCount = teamCount;
        width = side.dimensions().width();
        length = side.dimensions().length();
        centreX = teamCount == 4 ? length : 0;
    }

    public TerrainModel side() {
        return side;
    }

    public int teamCount() {
        return teamCount;
    }

    public int width() {
        return width + 2 * centreX;
    }

    public int length() {
        return width + 2 * length;
    }

    public int height() {
        return side.dimensions().height();
    }

    public Bounds bounds() {
        return new Bounds(0, 0, 0, width(), height(), length());
    }

    /** The centre footprint over the full map height; its top floor is centreSurfaceY(). */
    public Bounds centreBounds() {
        return new Bounds(centreX, 0, length, centreX + width, height(), length + width);
    }

    /**
     * Side to map coordinates. Coordinates may extend outside the side, for example to
     * step from its centre edge into the centre square; the transform is affine.
     */
    public Position toMap(int team, int x, int y, int z) {
        int u = x;
        int v = z - length;
        return switch (turns(team)) {
            case 0 -> new Position(centreX + u, y, length + v);
            case 1 -> new Position(centreX + width - 1 - v, y, length + u);
            case 2 -> new Position(centreX + width - 1 - u, y, length + width - 1 - v);
            default -> new Position(centreX + v, y, length + width - 1 - u);
        };
    }

    /** Map to side coordinates, also outside the team's bounds; inverse of toMap. */
    public Position toSide(int team, int mapX, int y, int mapZ) {
        int u = mapX - centreX;
        int v = mapZ - length;
        return switch (turns(team)) {
            case 0 -> new Position(u, y, v + length);
            case 1 -> new Position(v, y, width - 1 - u + length);
            case 2 -> new Position(width - 1 - u, y, width - 1 - v + length);
            default -> new Position(width - 1 - v, y, u + length);
        };
    }

    /** All cells belonging to this team's side, including air above its terrain. */
    public Bounds sideBounds(int team) {
        return transformedBounds(team, 0, height(), length);
    }

    /**
     * The flat spawn pad's surface blocks, not player locations. A later solver chooses
     * spawn positions above these blocks. Includes all spawnDepth rows and the full width.
     */
    public Bounds spawnArea(int team) {
        return transformedBounds(team, side.spawnSurfaceY(), side.spawnSurfaceY() + 1,
                side.dimensions().spawnDepth());
    }

    public Facing facing(int team) {
        return switch (turns(team)) {
            case 0 -> new Facing(0, 1);
            case 1 -> new Facing(-1, 0);
            case 2 -> new Facing(0, -1);
            default -> new Facing(1, 0);
        };
    }

    /** The kind at a map cell; AIR outside every part, including outside the height. */
    public BlockKind at(int mapX, int y, int mapZ) {
        if (!bounds().contains(mapX, y, mapZ)) {
            return BlockKind.AIR;
        }
        if (centreBounds().contains(mapX, y, mapZ)) {
            // Extend the centre ledge's column, preserving its soil and rock layers.
            return side.at(0, y, side.centreEdgeZ());
        }
        for (int team = 1; team <= teamCount; team++) {
            Position local = toSide(team, mapX, y, mapZ);
            if (local.x() >= 0 && local.x() < width && local.z() >= 0 && local.z() < length) {
                return side.at(local.x(), y, local.z());
            }
        }
        return BlockKind.AIR;
    }

    private Bounds transformedBounds(int team, int minY, int maxY, int depth) {
        Position a = toMap(team, 0, minY, 0);
        Position b = toMap(team, width - 1, minY, depth - 1);
        return new Bounds(Math.min(a.x(), b.x()), minY, Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()) + 1, maxY, Math.max(a.z(), b.z()) + 1);
    }

    private int turns(int team) {
        if (team < 1 || team > teamCount) {
            throw new IllegalArgumentException("team must be 1 to " + teamCount);
        }
        return teamCount == 2 ? (team - 1) * 2 : team - 1;
    }
}
