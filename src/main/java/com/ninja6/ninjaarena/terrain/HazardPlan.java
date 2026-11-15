package com.ninja6.ninjaarena.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which lava of a generated map may flow, where its flow may go, and which blocks may
 * collapse. Pure logic over an {@link ArenaLayout}, in its map frame: no world, server or
 * clock, safe on any thread, and the same layout always gives the same plan.
 *
 * <p><b>Lava sources.</b> Every lava fall head ({@link BlockKind#LAVA_SOURCE}) and every held
 * ceiling cell ({@link BlockKind#HANGING_LAVA}) with room below it is a source. Its
 * <em>footprint</em> is every cell its real flow may fill: the source, the column below it
 * down to the first solid block, and a pool on that floor up to {@link #POOL_REACH} blocks
 * away in the four directions, as far as lava spreads on an overworld floor; where the pool
 * reaches a drop, the column below that cell down to the next floor. Cells outside the
 * side's {@link SideDimensions#hazardRow hazard rows} are left out, so no footprint reaches
 * a spawn zone, the centre or their blends, nor anything outside the map.
 *
 * <p><b>The route.</b> A source is left out if its footprint would put new lava in the space
 * of the crumble-free route ({@link MovementSolver#firmRouteSpace}, {@link #ROUTE_MARGIN}
 * block either side) or within a block of an enemy spawn. The sources kept are checked
 * together: with all their footprints full of lava, the side must still be crossable without
 * crumbling blocks ({@link MovementSolver#crossableWithLava}), or no source is kept. So the
 * guarantee {@link MovementSolver} gives for collapses holds with every source flowing.
 *
 * <p><b>Collapses.</b> Every crumbling block ({@link BlockKind#CRUMBLE},
 * {@link BlockKind#CRUMBLE_PLATFORM}). The route never relies on one.
 *
 * <p>Every side is the same model turned about the centre, so the plan is worked out on
 * one side and turned to each team. Sources are listed team by team for each side source in
 * turn, so the first {@code n * teams} are the same {@code n} sources on every side.
 */
public final class HazardPlan {

    /** How far lava spreads across a floor from where it lands, in blocks. */
    public static final int POOL_REACH = 3;

    /** Blocks kept clear of new lava on either side of the route's space. */
    public static final int ROUTE_MARGIN = 1;

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** What a lava source is in the model. */
    public enum Kind {
        /** A lava fall's head ({@link BlockKind#LAVA_SOURCE}); its column is lava already. */
        FALL,
        /** Lava held in a ceiling ({@link BlockKind#HANGING_LAVA}) until it is released. */
        HANGING
    }

    /**
     * One lava source on one team's side, in the map frame.
     *
     * @param footprint every cell its flow may fill, the source first
     */
    public record LavaSource(Kind kind, int team, ArenaLayout.Position source, List<ArenaLayout.Position> footprint) {

        public LavaSource {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
            footprint = List.copyOf(footprint);
        }
    }

    /** A block that may collapse, in the map frame, and its kind. */
    public record Crumble(ArenaLayout.Position block, BlockKind kind) {
    }

    private final List<LavaSource> sources;
    private final List<Crumble> crumble;
    private final int teams;

    private HazardPlan(List<LavaSource> sources, List<Crumble> crumble, int teams) {
        this.sources = List.copyOf(sources);
        this.crumble = List.copyOf(crumble);
        this.teams = teams;
    }

    /** Works out the plan for a layout, solving its side with the movement given. */
    public static HazardPlan of(ArenaLayout layout, MovementModel movement) {
        Objects.requireNonNull(layout, "layout");
        return of(layout, MovementSolver.solve(layout.side(), Objects.requireNonNull(movement, "movement")));
    }

    /** Works out the plan for a layout whose side is already solved. */
    public static HazardPlan of(ArenaLayout layout, MovementSolver solver) {
        if (solver.side() != layout.side()) {
            throw new IllegalArgumentException("the solver must be for the layout's side");
        }
        TerrainModel side = layout.side();
        SideDimensions size = side.dimensions();
        boolean[] keep = solver.firmRouteSpace(ROUTE_MARGIN);
        for (ArenaLayout.Position enemy : solver.enemySpawns()) {
            mark(keep, size, enemy.x() - 1, enemy.x() + 1, enemy.y() - 1, enemy.y() + 2, enemy.z() - 1,
                    enemy.z() + 1);
        }
        List<ArenaLayout.Position> heads = new ArrayList<>();
        List<Kind> kinds = new ArrayList<>();
        List<List<ArenaLayout.Position>> footprints = new ArrayList<>();
        boolean[] lava = new boolean[size.volume()];
        for (int z = 0; z < size.length(); z++) {
            if (!size.hazardRow(z)) {
                continue;
            }
            for (int x = 0; x < size.width(); x++) {
                for (int y = 1; y < size.height(); y++) {
                    BlockKind kind = side.at(x, y, z);
                    if (kind != BlockKind.LAVA_SOURCE && kind != BlockKind.HANGING_LAVA
                            || side.at(x, y - 1, z).solid()) {
                        continue;
                    }
                    List<ArenaLayout.Position> footprint = footprint(side, x, y, z);
                    if (touches(footprint, side, keep)) {
                        continue;
                    }
                    heads.add(new ArenaLayout.Position(x, y, z));
                    kinds.add(kind == BlockKind.LAVA_SOURCE ? Kind.FALL : Kind.HANGING);
                    footprints.add(footprint);
                    for (ArenaLayout.Position cell : footprint) {
                        lava[index(size, cell.x(), cell.y(), cell.z())] = true;
                    }
                }
            }
        }
        if (!heads.isEmpty() && !solver.crossableWithLava(lava)) {
            heads.clear();
        }
        List<LavaSource> sources = new ArrayList<>();
        for (int i = 0; i < heads.size(); i++) {
            for (int team = 1; team <= layout.teamCount(); team++) {
                ArenaLayout.Position head = heads.get(i);
                List<ArenaLayout.Position> turned = new ArrayList<>();
                for (ArenaLayout.Position cell : footprints.get(i)) {
                    turned.add(layout.toMap(team, cell.x(), cell.y(), cell.z()));
                }
                sources.add(new LavaSource(kinds.get(i), team, layout.toMap(team, head.x(), head.y(), head.z()),
                        turned));
            }
        }
        List<Crumble> crumble = new ArrayList<>();
        for (int z = 0; z < size.length(); z++) {
            for (int x = 0; x < size.width(); x++) {
                for (int y = 0; y < size.height(); y++) {
                    BlockKind kind = side.at(x, y, z);
                    if (kind == BlockKind.CRUMBLE || kind == BlockKind.CRUMBLE_PLATFORM) {
                        for (int team = 1; team <= layout.teamCount(); team++) {
                            crumble.add(new Crumble(layout.toMap(team, x, y, z), kind));
                        }
                    }
                }
            }
        }
        return new HazardPlan(sources, crumble, layout.teamCount());
    }

    /** The lava sources whose flow is allowed, team by team for each side source. */
    public List<LavaSource> sources() {
        return sources;
    }

    /** The blocks that may collapse. */
    public List<Crumble> crumble() {
        return crumble;
    }

    /** The layout's team count: sources come in groups of this many, one per team. */
    public int teams() {
        return teams;
    }

    /**
     * Whether a map cell is in a team side's hazard rows: inside the map, not in a spawn
     * zone, the centre, their blends or an empty corner.
     */
    public static boolean inHazardZone(ArenaLayout layout, int x, int y, int z) {
        if (!layout.bounds().contains(x, y, z) || layout.centreBounds().contains(x, y, z)) {
            return false;
        }
        SideDimensions size = layout.side().dimensions();
        for (int team = 1; team <= layout.teamCount(); team++) {
            ArenaLayout.Position local = layout.toSide(team, x, y, z);
            if (local.x() >= 0 && local.x() < size.width() && local.z() >= 0 && local.z() < size.length()) {
                return size.hazardRow(local.z());
            }
        }
        return false;
    }

    /** The source, its column and its pool, in side coordinates, kept to the hazard rows. */
    private static List<ArenaLayout.Position> footprint(TerrainModel side, int x, int y, int z) {
        SideDimensions size = side.dimensions();
        List<ArenaLayout.Position> cells = new ArrayList<>();
        boolean[] seen = new boolean[size.volume()];
        add(cells, seen, size, x, y, z);
        int landing = fall(side, cells, seen, x, y - 1, z);
        // Spread across the floor it lands on; where a cell has no floor, lava falls instead.
        int[] queue = new int[(2 * POOL_REACH + 1) * (2 * POOL_REACH + 1) * 3];
        int head = 0;
        int tail = 0;
        queue[tail++] = x;
        queue[tail++] = z;
        queue[tail++] = 0;
        while (head < tail) {
            int cx = queue[head++];
            int cz = queue[head++];
            int distance = queue[head++];
            if (distance >= POOL_REACH) {
                continue;
            }
            for (int[] direction : DIRECTIONS) {
                int nx = cx + direction[0];
                int nz = cz + direction[1];
                if (nx < 0 || nx >= size.width() || nz < 0 || nz >= size.length() || !size.hazardRow(nz)
                        || side.at(nx, landing, nz).solid() || seen[index(size, nx, landing, nz)]) {
                    continue;
                }
                add(cells, seen, size, nx, landing, nz);
                if (landing > 0 && !side.at(nx, landing - 1, nz).solid()) {
                    fall(side, cells, seen, nx, landing - 1, nz);
                } else {
                    queue[tail++] = nx;
                    queue[tail++] = nz;
                    queue[tail++] = distance + 1;
                }
            }
        }
        return cells;
    }

    /**
     * Adds the column from {@code y} down to the first solid block.
     *
     * @return the lowest cell added, where the flow lands; {@code y + 1} if none was
     */
    private static int fall(TerrainModel side, List<ArenaLayout.Position> cells, boolean[] seen, int x, int y, int z) {
        int at = y;
        while (at >= 0 && !side.at(x, at, z).solid()) {
            if (!seen[index(side.dimensions(), x, at, z)]) {
                add(cells, seen, side.dimensions(), x, at, z);
            }
            at--;
        }
        return at + 1;
    }

    private static void add(List<ArenaLayout.Position> cells, boolean[] seen, SideDimensions size, int x, int y,
            int z) {
        seen[index(size, x, y, z)] = true;
        cells.add(new ArenaLayout.Position(x, y, z));
    }

    /** Whether any cell that is not lava already lies in the kept space. */
    private static boolean touches(List<ArenaLayout.Position> footprint, TerrainModel side, boolean[] keep) {
        SideDimensions size = side.dimensions();
        for (ArenaLayout.Position cell : footprint) {
            if (keep[index(size, cell.x(), cell.y(), cell.z())] && !lava(side.at(cell.x(), cell.y(), cell.z()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean lava(BlockKind kind) {
        return kind == BlockKind.LAVA_SOURCE || kind == BlockKind.LAVA || kind == BlockKind.HANGING_LAVA;
    }

    private static void mark(boolean[] keep, SideDimensions size, int minX, int maxX, int minY, int maxY, int minZ,
            int maxZ) {
        for (int x = Math.max(0, minX); x <= Math.min(size.width() - 1, maxX); x++) {
            for (int z = Math.max(0, minZ); z <= Math.min(size.length() - 1, maxZ); z++) {
                for (int y = Math.max(0, minY); y <= Math.min(size.height() - 1, maxY); y++) {
                    keep[index(size, x, y, z)] = true;
                }
            }
        }
    }

    private static int index(SideDimensions size, int x, int y, int z) {
        return (z * size.width() + x) * size.height() + y;
    }
}
