package com.ninja6.ninjaarena.lobby;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.lobby.LobbyPad.Side;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The generated lobby for a number of pads: where each piece goes, and the layout the
 * rest of the plugin reads. Pure arithmetic; {@link #blueprint} turns it into blocks.
 *
 * <p><b>Pads by count.</b> One pad per arena slot, at most {@value #MAX_PADS}, in one ring
 * around the centre. One pad goes north; two north and south; three north, west and east.
 * From four, pads are shared round-robin north, south, west and east, so up to eight each
 * side takes at most two and beyond that the ring widens.
 *
 * <p><b>Sizes.</b> A pad is a {@value #PLATE} x {@value #PLATE} plate in a gold frame;
 * pads on a side share frame lines, {@value #STRIDE} blocks apart. The centre is
 * {@code 6 x (most pads on one side) + 1} blocks square and never under {@value #EMBLEM},
 * and the emblem is drawn at the whole-number scale {@code centre / 13}. Each side's pads
 * sit in a {@value #BAND}-deep band in the middle of that side, and the corners are
 * {@value #BAND}-block squares, so the floor is {@code centre + 14} blocks square, centred
 * on 0, {@value #FLOOR_Y}, 0.
 *
 * <p><b>Edges.</b> A barrier wall {@value #WALL_HEIGHT} blocks high stands on the floor's
 * outer ring. Posts stand at its corners and on every frame line, and each post carries a
 * tree picked from the variants with a random seeded by the layout's seed, so the same
 * pads and seed give the same lobby.
 */
public final class GeneratedLobby {

    public static final int MAX_PADS = 32;
    public static final int FLOOR_Y = 64;
    /** The side of the centre piece and of the emblem at scale 1. */
    public static final int EMBLEM = 13;
    /** The depth of a pad band and the side of a corner square. */
    public static final int BAND = 7;
    public static final int PLATE = 5;
    public static final int STRIDE = 6;
    public static final int WALL_HEIGHT = 15;
    /** How far a tree may reach from its post on each side. */
    public static final int TREE_REACH = 3;
    /** How far the bounds reach past the build: sideways, below and above. */
    static final int BOUNDS_SIDE = 4;
    static final int BOUNDS_BELOW = 8;
    static final int BOUNDS_ABOVE = 4;

    /** An edge post: a floor-ring block, in lobby-world coordinates, and its tree variant. */
    public record Post(int x, int z, int tree) { }

    private final int pads;
    private final long seed;
    private final Map<Side, Integer> perSide;
    private final int centre;
    private final int size;
    private final int half;
    private final List<LobbyPad> padList;
    private final List<Post> posts;
    private final LobbyLayout layout;

    private GeneratedLobby(int pads, long seed, int treeVariants) {
        if (pads < 1 || pads > MAX_PADS) {
            throw new IllegalArgumentException("pads must be 1 to " + MAX_PADS);
        }
        if (treeVariants < 1) {
            throw new IllegalArgumentException("there must be at least one tree variant");
        }
        this.pads = pads;
        this.seed = seed;
        this.perSide = Collections.unmodifiableMap(perSide(pads));
        this.centre = centreSize(pads);
        this.size = centre + 2 * BAND;
        this.half = (size - 1) / 2;
        this.padList = List.copyOf(placePads());
        this.posts = List.copyOf(placePosts(treeVariants));
        this.layout = buildLayout();
    }

    /** The layout for this many pads and seed, with trees picked from that many variants. */
    public static GeneratedLobby plan(int pads, long seed, int treeVariants) {
        return new GeneratedLobby(pads, seed, treeVariants);
    }

    /** The pads for an {@code arenas.max-active}: one per slot, 1 to {@value #MAX_PADS}. */
    public static int padsFor(int maxActive) {
        return Math.clamp(maxActive, 1, MAX_PADS);
    }

    /** How many pads each side takes, clockwise from north; every side is present. */
    public static Map<Side, Integer> perSide(int pads) {
        if (pads < 1 || pads > MAX_PADS) {
            throw new IllegalArgumentException("pads must be 1 to " + MAX_PADS);
        }
        Map<Side, Integer> counts = new EnumMap<>(Side.class);
        for (Side side : Side.values()) {
            counts.put(side, 0);
        }
        List<Side> order = switch (pads) {
            case 1 -> List.of(Side.NORTH);
            case 2 -> List.of(Side.NORTH, Side.SOUTH);
            case 3 -> List.of(Side.NORTH, Side.WEST, Side.EAST);
            default -> List.of(Side.NORTH, Side.SOUTH, Side.WEST, Side.EAST);
        };
        for (int i = 0; i < pads; i++) {
            counts.merge(order.get(i % order.size()), 1, Integer::sum);
        }
        return counts;
    }

    /** The centre's side: 6 x the most pads on one side + 1, and at least {@value #EMBLEM}. */
    public static int centreSize(int pads) {
        int most = perSide(pads).values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        return Math.max(EMBLEM, STRIDE * most + 1);
    }

    /** The emblem's whole-number scale on a centre this size: the size divided by 13, rounded down. */
    public static int emblemScale(int centre) {
        return centre / EMBLEM;
    }

    public int pads() {
        return pads;
    }

    public long seed() {
        return seed;
    }

    public Map<Side, Integer> sides() {
        return perSide;
    }

    public int centre() {
        return centre;
    }

    public int emblemScale() {
        return emblemScale(centre);
    }

    /** The floor's side in blocks. */
    public int floorSize() {
        return size;
    }

    /** The floor, one block high at {@value #FLOOR_Y}. */
    public ArenaBounds floor() {
        return new ArenaBounds(-half, FLOOR_Y, -half, half, FLOOR_Y, half);
    }

    /** The centre square at floor level. */
    public ArenaBounds centreSquare() {
        return box(BAND, BAND, BAND + centre - 1, BAND + centre - 1, FLOOR_Y, FLOOR_Y);
    }

    /** The corner squares at floor level, by {@code nw}, {@code ne}, {@code sw} and {@code se}. */
    public Map<String, ArenaBounds> corners() {
        int far = size - BAND;
        Map<String, ArenaBounds> corners = new LinkedHashMap<>();
        corners.put("nw", box(0, 0, BAND - 1, BAND - 1, FLOOR_Y, FLOOR_Y));
        corners.put("ne", box(far, 0, size - 1, BAND - 1, FLOOR_Y, FLOOR_Y));
        corners.put("sw", box(0, far, BAND - 1, size - 1, FLOOR_Y, FLOOR_Y));
        corners.put("se", box(far, far, size - 1, size - 1, FLOOR_Y, FLOOR_Y));
        return corners;
    }

    public List<LobbyPad> padList() {
        return padList;
    }

    /** The edge posts, clockwise from the north-west corner. */
    public List<Post> posts() {
        return posts;
    }

    /** What the rest of the plugin reads: bounds, spawn, areas, boards and pads. */
    public LobbyLayout layout() {
        return layout;
    }

    private List<LobbyPad> placePads() {
        List<LobbyPad> placed = new ArrayList<>();
        for (Side side : Side.values()) {
            int count = perSide.get(side);
            int width = STRIDE * count + 1;
            int offset = BAND + (centre - width) / 2;
            for (int i = 0; i < count; i++) {
                // Clockwise: north west to east, east north to south, south east to west,
                // west south to north.
                int along = switch (side) {
                    case NORTH, EAST -> offset + STRIDE * i;
                    case SOUTH, WEST -> offset + STRIDE * (count - 1 - i);
                };
                int across = switch (side) {
                    case NORTH, WEST -> 0;
                    case SOUTH, EAST -> size - BAND;
                };
                boolean alongX = side == Side.NORTH || side == Side.SOUTH;
                int x = alongX ? along : across;
                int z = alongX ? across : along;
                ArenaBounds frame = box(x, z, x + BAND - 1, z + BAND - 1, FLOOR_Y, FLOOR_Y);
                ArenaBounds plate = box(x + 1, z + 1, x + BAND - 2, z + BAND - 2, FLOOR_Y, FLOOR_Y);
                float yaw = switch (side) {
                    case NORTH -> 0;
                    case EAST -> 90;
                    case SOUTH -> 180;
                    case WEST -> -90;
                };
                ArenaPoint label = new ArenaPoint((plate.minX() + plate.maxX() + 1) / 2.0, FLOOR_Y + 3,
                        (plate.minZ() + plate.maxZ() + 1) / 2.0, yaw, 0);
                placed.add(new LobbyPad(placed.size(), side, frame, plate, label));
            }
        }
        return placed;
    }

    /** Ring positions of the posts along one side: both ends and every frame line. */
    private List<Integer> postLine() {
        List<Integer> line = new ArrayList<>();
        line.add(0);
        for (int at = BAND; at <= BAND + centre - 1; at += STRIDE) {
            line.add(at);
        }
        line.add(size - 1);
        return line;
    }

    private List<Post> placePosts(int treeVariants) {
        List<Integer> line = postLine();
        List<int[]> ring = new ArrayList<>();
        for (int at : line) {
            ring.add(new int[] {at, 0});
        }
        for (int at : line.subList(1, line.size())) {
            ring.add(new int[] {size - 1, at});
        }
        for (int at : line.reversed().subList(1, line.size())) {
            ring.add(new int[] {at, size - 1});
        }
        for (int at : line.reversed().subList(1, line.size() - 1)) {
            ring.add(new int[] {0, at});
        }
        Random random = new Random(seed);
        List<Post> placed = new ArrayList<>();
        for (int[] at : ring) {
            placed.add(new Post(at[0] - half, at[1] - half, random.nextInt(treeVariants)));
        }
        return placed;
    }

    private LobbyLayout buildLayout() {
        ArenaBounds floor = floor();
        ArenaBounds build = new ArenaBounds(floor.minX() - TREE_REACH, FLOOR_Y - 1, floor.minZ() - TREE_REACH,
                floor.maxX() + TREE_REACH, FLOOR_Y + WALL_HEIGHT, floor.maxZ() + TREE_REACH);
        ArenaBounds bounds = new ArenaBounds(build.minX() - BOUNDS_SIDE, build.minY() - BOUNDS_BELOW,
                build.minZ() - BOUNDS_SIDE, build.maxX() + BOUNDS_SIDE, build.maxY() + BOUNDS_ABOVE,
                build.maxZ() + BOUNDS_SIDE);
        // On the middle of the emblem, facing north.
        ArenaPoint spawn = new ArenaPoint(0.5, FLOOR_Y + 1, 0.5, 180, 0);
        // Inside the wall: the floor's inner square, three blocks high.
        ArenaBounds roomArea = box(1, 1, size - 2, size - 2, FLOOR_Y + 1, FLOOR_Y + 3);
        // The south-east corner square inside the wall.
        int far = size - BAND;
        ArenaBounds shopArea = box(far, far, size - 2, size - 2, FLOOR_Y + 1, FLOOR_Y + 3);
        Map<String, ArenaPoint> boards = new LinkedHashMap<>();
        // Each in its corner's inner corner by the walls, facing the centre.
        boards.put("create-room", point(1, 1, -45));
        boards.put("room-list", point(size - 2, 1, 45));
        boards.put("shop", point(size - 2, size - 2, 135));
        return new LobbyLayout(build, bounds, spawn, roomArea, shopArea, boards, padList);
    }

    private ArenaPoint point(int relX, int relZ, float yaw) {
        return new ArenaPoint(relX - half + 0.5, FLOOR_Y + 2, relZ - half + 0.5, yaw, 0);
    }

    /** A box from floor-relative corners, where 0, 0 is the floor's north-west block. */
    private ArenaBounds box(int x1, int z1, int x2, int z2, int y1, int y2) {
        return new ArenaBounds(x1 - half, y1, z1 - half, x2 - half, y2, z2 - half);
    }

    /**
     * Every block the lobby places and every cushion, in lobby-world coordinates. Later
     * pieces replace earlier ones where they meet: the floor, the centre, the pads, the
     * corners, the wall and then the trees.
     */
    public Blueprint blueprint(LobbyPieces pieces) {
        if (pieces.trees().size() <= posts.stream().mapToInt(Post::tree).max().orElse(0)) {
            throw new IllegalArgumentException("the layout picks trees the pieces do not have");
        }
        Blueprint print = new Blueprint();
        ArenaBounds floor = floor();
        String floorBlock = pieces.floorBlock();
        for (int x = floor.minX(); x <= floor.maxX(); x++) {
            for (int z = floor.minZ(); z <= floor.maxZ(); z++) {
                print.put(x, FLOOR_Y, z, floorBlock);
            }
        }
        drawCentre(print, pieces.centre());
        for (LobbyPad pad : padList) {
            stamp(print, pieces.pad(), pad.frame().minX(), FLOOR_Y, pad.frame().minZ());
        }
        corners().forEach((name, square) -> stamp(print, pieces.corners().get(name), square.minX(), FLOOR_Y,
                square.minZ()));
        LobbyPiece wall = pieces.wall();
        for (int x = floor.minX(); x <= floor.maxX(); x++) {
            for (int z = floor.minZ(); z <= floor.maxZ(); z++) {
                if (x == floor.minX() || x == floor.maxX() || z == floor.minZ() || z == floor.maxZ()) {
                    stamp(print, wall, x, FLOOR_Y + 1, z);
                }
            }
        }
        for (Post post : posts) {
            stamp(print, pieces.trees().get(post.tree()), post.x() - TREE_REACH, FLOOR_Y + 1, post.z() - TREE_REACH);
        }
        return print;
    }

    /** Stone under the whole centre, the wool layer over it with the emblem scaled up. */
    private void drawCentre(Blueprint print, LobbyPiece piece) {
        String[][] under = new String[EMBLEM][EMBLEM];
        String[][] top = new String[EMBLEM][EMBLEM];
        for (LobbyPiece.Block block : piece.blocks()) {
            (block.y() == 0 ? under : top)[block.x()][block.z()] = block.state();
        }
        ArenaBounds square = centreSquare();
        String stone = under[0][0];
        String ground = top[0][0];
        int scale = emblemScale();
        int drawn = EMBLEM * scale;
        int offset = (centre - drawn) / 2;
        for (int dx = 0; dx < centre; dx++) {
            for (int dz = 0; dz < centre; dz++) {
                int x = square.minX() + dx;
                int z = square.minZ() + dz;
                if (stone != null) {
                    print.put(x, FLOOR_Y - 1, z, stone);
                }
                int ex = dx - offset;
                int ez = dz - offset;
                String block = ex >= 0 && ez >= 0 && ex < drawn && ez < drawn ? top[ex / scale][ez / scale] : ground;
                if (block != null) {
                    print.put(x, FLOOR_Y, z, block);
                }
            }
        }
    }

    /** Places a piece with its lowest north-west corner at a block, its first layer at {@code y}. */
    private static void stamp(Blueprint print, LobbyPiece piece, int x, int y, int z) {
        for (LobbyPiece.Block block : piece.blocks()) {
            print.put(x + block.x(), y + block.y(), z + block.z(), block.state());
        }
        for (LobbyPiece.Cushion cushion : piece.cushions()) {
            print.cushions.add(new LobbyPiece.Cushion(x + cushion.x(), y + cushion.y(), z + cushion.z(),
                    cushion.yaw(), cushion.color()));
        }
    }

    /** The blocks and cushions of a generated lobby, by lobby-world block. */
    public static final class Blueprint {

        private final Map<Long, String> blocks = new LinkedHashMap<>();
        private final List<LobbyPiece.Cushion> cushions = new ArrayList<>();

        void put(int x, int y, int z, String state) {
            blocks.put(key(x, y, z), state);
        }

        /** The block at a position, or null where the lobby has air. */
        public String at(int x, int y, int z) {
            return blocks.get(key(x, y, z));
        }

        public int size() {
            return blocks.size();
        }

        public List<LobbyPiece.Cushion> cushions() {
            return List.copyOf(cushions);
        }

        /** Every block, as packed positions ({@link #key}) to block states. */
        public Map<Long, String> blocks() {
            return Collections.unmodifiableMap(blocks);
        }

        /** Packs a block position; y within -2048 to 2047, x and z within 26 bits each side. */
        public static long key(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }
    }
}
