package com.ninja6.ninjaarena.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * Finds out whether players can cross a generated side from their spawn to the centre, how
 * hard the crossing is, and where spawns go. Pure logic over a {@link TerrainModel}: no
 * world, server, scheduler, clock or configuration, safe on any thread, and the same input
 * always gives the same answer.
 *
 * <p>A <em>standing cell</em> is the block a player's feet are in: air, with air above it
 * for the head and a solid block below that is not lava. Crumbling blocks are solid until
 * they collapse, so they can be stood on. Lava of any kind is never stood in or passed
 * through. Moves between standing cells follow {@link MovementModel} in the four
 * horizontal directions; a jump needs {@link #JUMP_CLEARANCE} blocks of air above its
 * take-off, over the gap and at its landing, measured from the higher of the two ends.
 *
 * <p>A side is crossable when the centre ledge can be reached from the team spawn. It must
 * also be crossable without relying on any crumbling block: a second search treats every
 * crumbling cell as blocked, neither a floor nor air, so its route never stands on one,
 * walks or drops through one, or needs one as headroom or jump clearance. A collapse only
 * turns crumbling cells into air, so every move of that route still works whichever
 * crumbling blocks have collapsed, in any order.
 *
 * <p>The challenge is measured on the <em>direct route</em>: the shortest route by
 * horizontal distance, and of those the one with the fewest jumps over a gap, then the
 * least climb, then the fewest hazards. Its measures are therefore what a player heading
 * straight for the centre cannot avoid.
 *
 * <p>Every team's side is the same model turned about the centre, and turns map the four
 * move directions onto each other, so one side answers for all of them. Outside the side
 * the full map holds only air (beyond the spawn edge and in the empty corners) or the
 * centre square (beyond the centre edge), so no route leaves a side except into the
 * centre. {@link #crossLayout} runs the same search over a whole {@link ArenaLayout} so
 * tests can confirm this.
 */
public final class MovementSolver {

    /** Enemy spawn points chosen on each side; the full map gets this many per team. */
    public static final int ENEMY_SPAWNS_PER_SIDE = 3;

    /** Enemy spawns keep at least this far from the team spawn, horizontally. */
    public static final int ENEMY_SPAWN_DISTANCE = 16;

    /** Blocks of air a jump needs from the higher end's feet up. */
    public static final int JUMP_CLEARANCE = 3;

    private static final byte AIR = (byte) BlockKind.AIR.ordinal();
    private static final boolean[] SOLID = new boolean[BlockKind.values().length];
    private static final boolean[] HAZARD = new boolean[BlockKind.values().length];
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    static {
        for (BlockKind kind : BlockKind.values()) {
            SOLID[kind.ordinal()] = kind.solid();
            HAZARD[kind.ordinal()] = isHazard(kind);
        }
    }

    private final TerrainModel side;
    private final MovementModel movement;
    private final Graph intact;
    private final int spawnNode;
    private final boolean[] reached;
    private final List<Integer> enemyNodes;
    private final CrossingReport report;

    private MovementSolver(TerrainModel side, MovementModel movement) {
        this.side = side;
        this.movement = movement;
        Cells cells = Cells.of(side);
        intact = new Graph(cells, movement);
        spawnNode = chooseSpawn();
        if (spawnNode < 0) {
            reached = new boolean[intact.nodes];
            enemyNodes = List.of();
            report = CrossingReport.unsafeSpawn(side.seed(), side.difficulty(), intact.nodes);
            return;
        }
        reached = intact.reach(spawnNode);
        enemyNodes = enemySpawnNodes();
        SideDimensions size = side.dimensions();
        int ledge = size.length() - size.centreDepth();
        int feet = side.centreSurfaceY() + 1;
        report = measure(side, cells, intact, intact.position(spawnNode), reached, movement,
                (graph, node) -> graph.z(node) >= ledge && graph.y(node) == feet, enemyNodes.size());
    }

    /** Solves one side. Builds the move graph once; queries on the result are cheap. */
    public static MovementSolver solve(TerrainModel side, MovementModel movement) {
        return new MovementSolver(Objects.requireNonNull(side, "side"), Objects.requireNonNull(movement, "movement"));
    }

    /** Whether a cell is a hazard for the challenge check: any lava, or a block that may collapse. */
    public static boolean isHazard(BlockKind kind) {
        return switch (kind) {
            case LAVA_SOURCE, LAVA, HANGING_LAVA, CRUMBLE, CRUMBLE_PLATFORM -> true;
            default -> false;
        };
    }

    public TerrainModel side() {
        return side;
    }

    public MovementModel movement() {
        return movement;
    }

    public CrossingReport report() {
        return report;
    }

    /** Whether a player can stand with their feet in this side cell. */
    public boolean standable(int x, int y, int z) {
        return intact.cells.standable(x, y, z);
    }

    /** Whether a player can walk, climb, drop or jump from the team spawn to this standing cell. */
    public boolean reachable(int x, int y, int z) {
        int node = intact.nodeAt(x, y, z);
        return node >= 0 && reached[node];
    }

    /** Hazard blocks around a player standing here: see {@link CrossingReport#hazardExposure()}. */
    public int hazardsAround(int x, int y, int z) {
        return intact.cells.hazardsAround(x, y, z);
    }

    /** What is wrong with putting a team spawn's feet in this side cell, if anything. */
    public SpawnCheck checkSpawn(int x, int y, int z) {
        Cells cells = intact.cells;
        byte feet = cells.kind(x, y, z);
        byte head = cells.kind(x, y + 1, z);
        if (isLava(feet) || isLava(head)) {
            return SpawnCheck.IN_LAVA;
        }
        if (feet != AIR || head != AIR) {
            return SpawnCheck.IN_WALL;
        }
        byte floor = cells.kind(x, y - 1, z);
        if (isLava(floor)) {
            return SpawnCheck.IN_LAVA;
        }
        if (floor != BlockKind.SPAWN_PAD.ordinal()) {
            return SpawnCheck.NOT_ON_SPAWN_PAD;
        }
        if (cells.hazardsAround(x, y, z) > 0) {
            return SpawnCheck.BESIDE_HAZARD;
        }
        return SpawnCheck.SAFE;
    }

    /** Why a cell cannot hold a team spawn. */
    public enum SpawnCheck {
        SAFE, IN_LAVA, IN_WALL, NOT_ON_SPAWN_PAD, BESIDE_HAZARD
    }

    /** The team spawn's feet in side coordinates, or {@code null} if no pad cell is safe. */
    public ArenaLayout.Position spawn() {
        return spawnNode < 0 ? null : intact.position(spawnNode);
    }

    /** The enemy spawn points' feet in side coordinates, in pick order. */
    public List<ArenaLayout.Position> enemySpawns() {
        List<ArenaLayout.Position> positions = new ArrayList<>();
        for (int node : enemyNodes) {
            positions.add(intact.position(node));
        }
        return positions;
    }

    /**
     * The points a playable arena needs, in the layout's map frame. Empty if the side has no
     * safe spawn. Team spawns stand on the middle of each spawn pad facing the centre; enemy
     * spawns are up to {@link #ENEMY_SPAWNS_PER_SIDE} reachable, hazard-free standing cells
     * per side, at least {@link #ENEMY_SPAWN_DISTANCE} from the team spawn and spread out,
     * turned to every team so each team's side gets the same ones. The waiting room stands
     * in the middle of the centre square, which is flat, open to the sky and clear of hazards.
     */
    public Optional<MapPoints> points(ArenaLayout layout) {
        if (layout.side() != side) {
            throw new IllegalArgumentException("the layout must be built on this side");
        }
        if (spawnNode < 0) {
            return Optional.empty();
        }
        List<MapPoints.Point> teams = new ArrayList<>();
        List<MapPoints.Point> enemies = new ArrayList<>();
        for (int team = 1; team <= layout.teamCount(); team++) {
            float yaw = yaw(layout.facing(team));
            teams.add(mapPoint(layout, team, spawnNode, yaw));
            for (int node : enemyNodes) {
                enemies.add(mapPoint(layout, team, node, yaw));
            }
        }
        ArenaLayout.Bounds centre = layout.centreBounds();
        int waitingX = (centre.minX() + centre.maxX()) / 2;
        int waitingZ = (centre.minZ() + centre.maxZ()) / 2;
        MapPoints.Point waiting = new MapPoints.Point(waitingX + 0.5, side.centreSurfaceY() + 1, waitingZ + 0.5, 0);
        return Optional.of(new MapPoints(teams, enemies, waiting));
    }

    /**
     * Runs the crossing search over a whole layout, from a team's spawn (the side spawn turned
     * into the map) to any standing cell over the centre square. Slow: it snapshots the full
     * map. For checking that one side answers for all teams; generation uses {@link #solve}.
     * The standing and reachable counts are those of the whole map.
     */
    public static CrossingReport crossLayout(ArenaLayout layout, int team, MovementModel movement) {
        MovementSolver solved = solve(layout.side(), movement);
        Cells cells = Cells.of(layout);
        Graph graph = new Graph(cells, movement);
        ArenaLayout.Position spawn = solved.spawn();
        if (spawn == null) {
            return CrossingReport.unsafeSpawn(layout.side().seed(), layout.side().difficulty(), graph.nodes);
        }
        ArenaLayout.Position start = layout.toMap(team, spawn.x(), spawn.y(), spawn.z());
        ArenaLayout.Bounds centre = layout.centreBounds();
        return measure(layout.side(), cells, graph, start, graph.reach(graph.nodeAt(start.x(), start.y(), start.z())),
                movement, (g, node) -> centre.contains(g.x(node), 0, g.z(node)), solved.enemyNodes.size());
    }

    /** Which nodes of a graph are the centre a route ends at. */
    private interface Target {
        boolean test(Graph graph, int node);
    }

    private static CrossingReport measure(TerrainModel side, Cells cells, Graph graph, ArenaLayout.Position start,
            boolean[] reach, MovementModel movement, Target target, int enemies) {
        int source = graph.nodeAt(start.x(), start.y(), start.z());
        boolean[] targets = graph.targets(node -> target.test(graph, node));
        long route = graph.directRoute(source, targets);
        int reachable = 0;
        for (boolean r : reach) {
            if (r) {
                reachable++;
            }
        }
        boolean withoutCrumble = false;
        if (route >= 0) {
            Graph firm = new Graph(cells.withoutCrumble(), movement);
            int firmSource = firm.nodeAt(start.x(), start.y(), start.z());
            withoutCrumble = firmSource >= 0 && firm.reaches(firmSource,
                    firm.targets(node -> target.test(firm, node)));
        }
        if (route < 0) {
            return new CrossingReport(side.seed(), side.difficulty(), true, false, false, -1, -1, -1, -1,
                    graph.nodes, reachable, enemies);
        }
        return new CrossingReport(side.seed(), side.difficulty(), true, true, withoutCrumble, Graph.length(route),
                Graph.jumps(route), Graph.climb(route), Graph.hazards(route), graph.nodes, reachable, enemies);
    }

    /** The safe pad cell nearest the middle of the spawn zone, ties to the lowest x then z. */
    private int chooseSpawn() {
        SideDimensions size = side.dimensions();
        int y = side.spawnSurfaceY() + 1;
        int middleX = size.width() / 2;
        int middleZ = size.spawnDepth() / 2;
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int x = 0; x < size.width(); x++) {
            for (int z = 0; z < size.spawnDepth(); z++) {
                if (checkSpawn(x, y, z) != SpawnCheck.SAFE) {
                    continue;
                }
                int distance = (x - middleX) * (x - middleX) + (z - middleZ) * (z - middleZ);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = intact.nodeAt(x, y, z);
                }
            }
        }
        return best;
    }

    /**
     * Farthest-point picks among reachable, hazard-free standing cells beyond the spawn zone
     * and away from the spawn: each pick is the candidate farthest from the spawn and the
     * earlier picks, ties to the lowest cell index.
     */
    private List<Integer> enemySpawnNodes() {
        SideDimensions size = side.dimensions();
        int minDistance = Math.min(ENEMY_SPAWN_DISTANCE, size.length() / 2);
        int spawnX = intact.x(spawnNode);
        int spawnZ = intact.z(spawnNode);
        int[] candidates = new int[intact.nodes];
        int count = 0;
        for (int node = 0; node < intact.nodes; node++) {
            int x = intact.x(node);
            int z = intact.z(node);
            int dx = x - spawnX;
            int dz = z - spawnZ;
            byte floor = intact.cells.kind(x, intact.y(node) - 1, z);
            if (reached[node] && z >= size.spawnDepth() && dx * dx + dz * dz >= minDistance * minDistance
                    && !HAZARD[floor] && intact.exposure[node] == 0) {
                candidates[count++] = node;
            }
        }
        int[] nearest = new int[count];
        for (int i = 0; i < count; i++) {
            int dx = intact.x(candidates[i]) - spawnX;
            int dz = intact.z(candidates[i]) - spawnZ;
            nearest[i] = dx * dx + dz * dz;
        }
        List<Integer> picks = new ArrayList<>();
        while (picks.size() < ENEMY_SPAWNS_PER_SIDE) {
            int best = -1;
            for (int i = 0; i < count; i++) {
                if (nearest[i] > 0 && (best < 0 || nearest[i] > nearest[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            int pick = candidates[best];
            picks.add(pick);
            for (int i = 0; i < count; i++) {
                int dx = intact.x(candidates[i]) - intact.x(pick);
                int dz = intact.z(candidates[i]) - intact.z(pick);
                nearest[i] = Math.min(nearest[i], dx * dx + dz * dz);
            }
        }
        return List.copyOf(picks);
    }

    private MapPoints.Point mapPoint(ArenaLayout layout, int team, int node, float yaw) {
        ArenaLayout.Position at = layout.toMap(team, intact.x(node), intact.y(node), intact.z(node));
        return new MapPoints.Point(at.x() + 0.5, at.y(), at.z() + 0.5, yaw);
    }

    /** Minecraft yaw for a facing: 0 is positive z, 90 negative x, 180 negative z, -90 positive x. */
    static float yaw(ArenaLayout.Facing facing) {
        if (facing.dz() > 0) {
            return 0;
        }
        if (facing.dz() < 0) {
            return 180;
        }
        return facing.dx() < 0 ? 90 : -90;
    }

    private static boolean isLava(byte kind) {
        return HAZARD[kind] && !SOLID[kind];
    }

    /** A snapshot of kinds over a box, with air outside it. */
    static final class Cells {

        final int sizeX;
        final int sizeY;
        final int sizeZ;
        private final byte[] kinds;

        private Cells(int sizeX, int sizeY, int sizeZ, byte[] kinds) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.kinds = kinds;
        }

        static Cells of(TerrainModel side) {
            SideDimensions size = side.dimensions();
            return new Cells(size.width(), size.height(), size.length(), side.copyCells());
        }

        static Cells of(ArenaLayout layout) {
            byte[] kinds = new byte[layout.width() * layout.height() * layout.length()];
            int i = 0;
            for (int z = 0; z < layout.length(); z++) {
                for (int x = 0; x < layout.width(); x++) {
                    for (int y = 0; y < layout.height(); y++) {
                        kinds[i++] = (byte) layout.at(x, y, z).ordinal();
                    }
                }
            }
            return new Cells(layout.width(), layout.height(), layout.length(), kinds);
        }

        /**
         * The same cells with every crumbling block blocked: read as lava, which is neither
         * solid nor air, so no move stands on it, passes through it or counts it as clearance.
         * Only moves that hold whether or not each crumbling block has collapsed remain.
         */
        Cells withoutCrumble() {
            byte[] firm = kinds.clone();
            byte crumble = (byte) BlockKind.CRUMBLE.ordinal();
            byte platform = (byte) BlockKind.CRUMBLE_PLATFORM.ordinal();
            byte blocked = (byte) BlockKind.LAVA.ordinal();
            for (int i = 0; i < firm.length; i++) {
                if (firm[i] == crumble || firm[i] == platform) {
                    firm[i] = blocked;
                }
            }
            return new Cells(sizeX, sizeY, sizeZ, firm);
        }

        int index(int x, int y, int z) {
            return (z * sizeX + x) * sizeY + y;
        }

        byte kind(int x, int y, int z) {
            if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
                return AIR;
            }
            return kinds[index(x, y, z)];
        }

        boolean air(int x, int y, int z) {
            return kind(x, y, z) == AIR;
        }

        boolean standable(int x, int y, int z) {
            return x >= 0 && x < sizeX && z >= 0 && z < sizeZ && y >= 1 && y < sizeY
                    && SOLID[kind(x, y - 1, z)] && air(x, y, z) && air(x, y + 1, z);
        }

        /** Hazards in the 3 by 3 columns around a player, from the floor to above the head. */
        int hazardsAround(int x, int y, int z) {
            int count = 0;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        if (HAZARD[kind(x + dx, y + dy, z + dz)]) {
                            count++;
                        }
                    }
                }
            }
            return count;
        }
    }

    /** Standing cells as nodes and the moves between them as edges, in cell order. */
    static final class Graph {

        // A route's cost packs, from the most significant field: horizontal length (19 bits),
        // jumps (12), climb (12) and hazards (20). Each saturates instead of overflowing.
        private static final int HAZARD_BITS = 20;
        private static final int CLIMB_BITS = 12;
        private static final int JUMP_BITS = 12;
        private static final int LENGTH_BITS = 19;
        private static final int CLIMB_SHIFT = HAZARD_BITS;
        private static final int JUMP_SHIFT = CLIMB_SHIFT + CLIMB_BITS;
        private static final int LENGTH_SHIFT = JUMP_SHIFT + JUMP_BITS;

        final Cells cells;
        final int nodes;
        final int[] exposure;
        private final int[] nodeOf;
        private final int[] cellOf;
        private final int[] edgeStart;
        private final int[] edgeTo;
        private final byte[] edgeLength;
        private final byte[] edgeJump;
        private final byte[] edgeClimb;

        Graph(Cells cells, MovementModel movement) {
            this.cells = cells;
            nodeOf = new int[cells.sizeX * cells.sizeY * cells.sizeZ];
            Arrays.fill(nodeOf, -1);
            Ints standing = new Ints();
            byte[] kinds = cells.kinds;
            for (int column = 0; column < cells.sizeX * cells.sizeZ; column++) {
                int base = column * cells.sizeY;
                for (int y = 1; y < cells.sizeY; y++) {
                    int i = base + y;
                    if (SOLID[kinds[i - 1]] && kinds[i] == AIR && (y + 1 == cells.sizeY || kinds[i + 1] == AIR)) {
                        nodeOf[i] = standing.size;
                        standing.add(i);
                    }
                }
            }
            nodes = standing.size;
            cellOf = standing.toArray();
            exposure = new int[nodes];
            edgeStart = new int[nodes + 1];
            Ints to = new Ints();
            Ints length = new Ints();
            Ints jump = new Ints();
            Ints climb = new Ints();
            for (int node = 0; node < nodes; node++) {
                edgeStart[node] = to.size;
                int x = x(node);
                int y = y(node);
                int z = z(node);
                exposure[node] = cells.hazardsAround(x, y, z);
                for (int[] direction : DIRECTIONS) {
                    addMoves(movement, x, y, z, direction[0], direction[1], to, length, jump, climb);
                }
            }
            edgeStart[nodes] = to.size;
            edgeTo = to.toArray();
            edgeLength = length.toBytes();
            edgeJump = jump.toBytes();
            edgeClimb = climb.toBytes();
        }

        private void addMoves(MovementModel movement, int x, int y, int z, int dx, int dz, Ints to, Ints length,
                Ints jump, Ints climb) {
            int nx = x + dx;
            int nz = z + dz;
            if (cells.air(nx, y, nz) && cells.air(nx, y + 1, nz)) {
                if (cells.standable(nx, y, nz)) {
                    edge(nx, y, nz, 1, 0, 0, to, length, jump, climb);
                } else {
                    // Walk off the edge and drop, through air only.
                    for (int drop = 1; drop <= movement.safeFall(); drop++) {
                        int ly = y - drop;
                        if (ly < 1 || !cells.air(nx, ly, nz)) {
                            break;
                        }
                        if (cells.standable(nx, ly, nz)) {
                            edge(nx, ly, nz, 1, 0, 0, to, length, jump, climb);
                            break;
                        }
                    }
                }
            } else {
                for (int rise = 1; rise <= movement.jumpHeight(); rise++) {
                    if (!cells.air(x, y + 1 + rise, z)) {
                        break;
                    }
                    if (cells.standable(nx, y + rise, nz)) {
                        edge(nx, y + rise, nz, 1, 0, rise, to, length, jump, climb);
                        break;
                    }
                }
            }
            // A jump starts at an edge: where the next column can be walked onto, walking
            // there first is as short and needs no jump.
            if (cells.standable(nx, y, nz)) {
                return;
            }
            for (int gap = 1; gap <= movement.maxGap(); gap++) {
                // Every jump this far passes over this column at least from the feet to the clearance.
                if (!airColumn(x + dx * gap, y, y + JUMP_CLEARANCE - 1, z + dz * gap)) {
                    break;
                }
                int distance = gap + 1;
                int lx = x + dx * distance;
                int lz = z + dz * distance;
                for (int dy = -movement.safeFall(); dy <= movement.jumpHeight(); dy++) {
                    if (dy > 0 && gap > movement.maxRisingGap()) {
                        break;
                    }
                    int ly = y + dy;
                    if (cells.standable(lx, ly, lz) && clearArc(x, y, z, dx, dz, distance, ly)) {
                        edge(lx, ly, lz, distance, 1, Math.max(0, dy), to, length, jump, climb);
                    }
                }
            }
        }

        private boolean airColumn(int x, int fromY, int toY, int z) {
            for (int y = fromY; y <= toY; y++) {
                if (!cells.air(x, y, z)) {
                    return false;
                }
            }
            return true;
        }

        /** Air in every column of a jump, from its feet up to the clearance above the higher end. */
        private boolean clearArc(int x, int y, int z, int dx, int dz, int distance, int landingY) {
            int top = Math.max(y, landingY) + JUMP_CLEARANCE - 1;
            for (int step = 0; step <= distance; step++) {
                int from = step == distance ? landingY : y;
                for (int cy = from; cy <= top; cy++) {
                    if (!cells.air(x + dx * step, cy, z + dz * step)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void edge(int x, int y, int z, int distance, int isJump, int rise, Ints to, Ints length, Ints jump,
                Ints climb) {
            to.add(nodeOf[cells.index(x, y, z)]);
            length.add(distance);
            jump.add(isJump);
            climb.add(rise);
        }

        int nodeAt(int x, int y, int z) {
            if (x < 0 || x >= cells.sizeX || y < 0 || y >= cells.sizeY || z < 0 || z >= cells.sizeZ) {
                return -1;
            }
            return nodeOf[cells.index(x, y, z)];
        }

        int x(int node) {
            return cellOf[node] / cells.sizeY % cells.sizeX;
        }

        int y(int node) {
            return cellOf[node] % cells.sizeY;
        }

        int z(int node) {
            return cellOf[node] / cells.sizeY / cells.sizeX;
        }

        ArenaLayout.Position position(int node) {
            return new ArenaLayout.Position(x(node), y(node), z(node));
        }

        boolean[] targets(IntPredicate target) {
            boolean[] targets = new boolean[nodes];
            for (int node = 0; node < nodes; node++) {
                targets[node] = target.test(node);
            }
            return targets;
        }

        boolean[] reach(int source) {
            boolean[] seen = new boolean[nodes];
            int[] queue = new int[nodes];
            int head = 0;
            int tail = 0;
            seen[source] = true;
            queue[tail++] = source;
            while (head < tail) {
                int node = queue[head++];
                for (int e = edgeStart[node]; e < edgeStart[node + 1]; e++) {
                    int next = edgeTo[e];
                    if (!seen[next]) {
                        seen[next] = true;
                        queue[tail++] = next;
                    }
                }
            }
            return seen;
        }

        boolean reaches(int source, boolean[] targets) {
            boolean[] seen = reach(source);
            for (int node = 0; node < nodes; node++) {
                if (seen[node] && targets[node]) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The direct route's packed cost, or {@code -1} if no target is reachable: Dijkstra on
         * the packed (length, jumps, climb, hazards) cost, so ties in length go to fewer jumps,
         * then less climb, then fewer hazards. The source's own hazards count.
         */
        long directRoute(int source, boolean[] targets) {
            long[] best = new long[nodes];
            Arrays.fill(best, Long.MAX_VALUE);
            Heap heap = new Heap();
            long start = pack(0, 0, 0, exposure[source]);
            best[source] = start;
            heap.push(start, source);
            while (heap.size > 0) {
                long cost = heap.topKey();
                int node = heap.pop();
                if (cost > best[node]) {
                    continue;
                }
                if (targets[node]) {
                    return cost;
                }
                for (int e = edgeStart[node]; e < edgeStart[node + 1]; e++) {
                    int next = edgeTo[e];
                    long total = pack(length(cost) + edgeLength[e], jumps(cost) + edgeJump[e],
                            climb(cost) + edgeClimb[e], hazards(cost) + exposure[next]);
                    if (total < best[next]) {
                        best[next] = total;
                        heap.push(total, next);
                    }
                }
            }
            return -1;
        }

        static long pack(int length, int jumps, int climb, int hazards) {
            return (long) saturate(length, LENGTH_BITS) << LENGTH_SHIFT
                    | (long) saturate(jumps, JUMP_BITS) << JUMP_SHIFT
                    | (long) saturate(climb, CLIMB_BITS) << CLIMB_SHIFT | saturate(hazards, HAZARD_BITS);
        }

        private static int saturate(int value, int bits) {
            return Math.min(value, (1 << bits) - 1);
        }

        static int length(long cost) {
            return (int) (cost >>> LENGTH_SHIFT) & ((1 << LENGTH_BITS) - 1);
        }

        static int jumps(long cost) {
            return (int) (cost >>> JUMP_SHIFT) & ((1 << JUMP_BITS) - 1);
        }

        static int climb(long cost) {
            return (int) (cost >>> CLIMB_SHIFT) & ((1 << CLIMB_BITS) - 1);
        }

        static int hazards(long cost) {
            return (int) cost & ((1 << HAZARD_BITS) - 1);
        }
    }

    /** A growable int list without boxing. */
    private static final class Ints {

        int[] values = new int[256];
        int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }

        byte[] toBytes() {
            byte[] bytes = new byte[size];
            for (int i = 0; i < size; i++) {
                bytes[i] = (byte) values[i];
            }
            return bytes;
        }
    }

    /** A binary min-heap of nodes keyed by cost; equal costs pop the lower node first. */
    private static final class Heap {

        long[] keys = new long[256];
        int[] values = new int[256];
        int size;

        void push(long key, int value) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, size * 2);
                values = Arrays.copyOf(values, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int parent = (i - 1) / 2;
                if (!less(key, value, keys[parent], values[parent])) {
                    break;
                }
                keys[i] = keys[parent];
                values[i] = values[parent];
                i = parent;
            }
            keys[i] = key;
            values[i] = value;
        }

        long topKey() {
            return keys[0];
        }

        int pop() {
            int top = values[0];
            long lastKey = keys[--size];
            int lastValue = values[size];
            int i = 0;
            while (true) {
                int child = 2 * i + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && less(keys[child + 1], values[child + 1], keys[child], values[child])) {
                    child++;
                }
                if (!less(keys[child], values[child], lastKey, lastValue)) {
                    break;
                }
                keys[i] = keys[child];
                values[i] = values[child];
                i = child;
            }
            keys[i] = lastKey;
            values[i] = lastValue;
            return top;
        }

        private static boolean less(long key, int value, long otherKey, int otherValue) {
            return key < otherKey || key == otherKey && value < otherValue;
        }
    }
}
