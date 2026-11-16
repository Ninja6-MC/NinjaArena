package com.ninja6.ninjaarena.lobby;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * One piece of the generated lobby, read from a structure file in {@code lobby/pieces/}:
 * its size, its blocks and its cushions, all relative to its lowest north-west corner.
 * Air is not stored, so placing a piece leaves its empty cells as they are.
 *
 * @param name     the piece's name in {@code generated-lobby.yml}
 * @param sizeX    width, west to east
 * @param sizeY    height
 * @param sizeZ    depth, north to south
 * @param blocks   the blocks, in the order the file lists them
 * @param cushions the cushions standing on the piece
 */
public record LobbyPiece(String name, int sizeX, int sizeY, int sizeZ, List<Block> blocks,
        List<Cushion> cushions) {

    /** A block of the piece: a block state such as {@code minecraft:green_wool}. */
    public record Block(int x, int y, int z, String state) {

        public Block {
            Objects.requireNonNull(state, "state");
        }
    }

    /** A cushion entity at a piece-relative position; yaw 0 faces south. */
    public record Cushion(double x, double y, double z, float yaw, String color) {

        public Cushion {
            Objects.requireNonNull(color, "color");
        }
    }

    public LobbyPiece {
        Objects.requireNonNull(name, "name");
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new IllegalArgumentException(name + " must be at least 1 on each axis");
        }
        blocks = List.copyOf(blocks);
        cushions = List.copyOf(cushions);
        for (Block block : blocks) {
            if (block.x() < 0 || block.y() < 0 || block.z() < 0 || block.x() >= sizeX || block.y() >= sizeY
                    || block.z() >= sizeZ) {
                throw new IllegalArgumentException("a block of " + name + " is outside its size");
            }
        }
    }

    /**
     * Reads a gzip-compressed vanilla structure file as written by the piece generator: the
     * size, a palette of plain block names, the blocks and the cushion entities.
     */
    public static LobbyPiece read(String name, InputStream compressed) throws IOException {
        Map<String, Object> root;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(compressed))) {
            if (in.readByte() != COMPOUND) {
                throw new IOException(name + " is not a structure file");
            }
            in.readUTF();
            root = compound(in);
        }
        List<Integer> size = ints(root.get("size"), name + " size");
        if (size.size() != 3) {
            throw new IOException(name + " size must be three whole numbers");
        }
        List<String> palette = new ArrayList<>();
        for (Object entry : list(root.get("palette"), name + " palette")) {
            palette.add(string(map(entry, name + " palette").get("Name"), name + " palette name"));
        }
        List<Block> blocks = new ArrayList<>();
        for (Object entry : list(root.get("blocks"), name + " blocks")) {
            Map<String, Object> block = map(entry, name + " block");
            List<Integer> pos = ints(block.get("pos"), name + " block pos");
            Object state = block.get("state");
            if (pos.size() != 3 || !(state instanceof Integer index) || index < 0 || index >= palette.size()) {
                throw new IOException(name + " has a block with no position or state");
            }
            blocks.add(new Block(pos.get(0), pos.get(1), pos.get(2), palette.get(index)));
        }
        List<Cushion> cushions = new ArrayList<>();
        Object entities = root.get("entities");
        for (Object entry : entities == null ? List.of() : list(entities, name + " entities")) {
            Map<String, Object> entity = map(entry, name + " entity");
            Map<String, Object> data = map(entity.get("nbt"), name + " entity nbt");
            if (!"minecraft:cushion".equals(data.get("id"))) {
                continue;
            }
            List<?> pos = list(entity.get("pos"), name + " entity pos");
            List<?> rotation = list(data.get("Rotation"), name + " entity rotation");
            if (pos.size() != 3 || !pos.stream().allMatch(Double.class::isInstance) || rotation.isEmpty()
                    || !(rotation.getFirst() instanceof Float yaw)) {
                throw new IOException(name + " has a cushion with no position or rotation");
            }
            cushions.add(new Cushion((Double) pos.get(0), (Double) pos.get(1), (Double) pos.get(2), yaw,
                    string(data.get("color"), name + " cushion color")));
        }
        try {
            return new LobbyPiece(name, size.get(0), size.get(1), size.get(2), blocks, cushions);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static final byte END = 0;
    private static final byte BYTE = 1;
    private static final byte SHORT = 2;
    private static final byte INT = 3;
    private static final byte LONG = 4;
    private static final byte FLOAT = 5;
    private static final byte DOUBLE = 6;
    private static final byte STRING = 8;
    private static final byte LIST = 9;
    private static final byte COMPOUND = 10;
    /** Deeper nesting than a structure file has is refused, not followed. */
    private static final int MAX_DEPTH = 16;
    private static final int MAX_LIST = 1 << 20;

    private static Map<String, Object> compound(DataInputStream in) throws IOException {
        return compound(in, 0);
    }

    private static Map<String, Object> compound(DataInputStream in, int depth) throws IOException {
        Map<String, Object> values = new HashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == END) {
                return values;
            }
            String key = in.readUTF();
            values.put(key, payload(in, type, depth + 1));
        }
    }

    private static Object payload(DataInputStream in, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("structure file nested too deep");
        }
        return switch (type) {
            case BYTE -> in.readByte();
            case SHORT -> in.readShort();
            case INT -> in.readInt();
            case LONG -> in.readLong();
            case FLOAT -> in.readFloat();
            case DOUBLE -> in.readDouble();
            case STRING -> in.readUTF();
            case LIST -> {
                byte element = in.readByte();
                int length = in.readInt();
                if (length < 0 || length > MAX_LIST) {
                    throw new IOException("structure file list of " + length);
                }
                List<Object> values = new ArrayList<>(Math.min(length, 4096));
                for (int i = 0; i < length; i++) {
                    values.add(payload(in, element, depth + 1));
                }
                yield values;
            }
            case COMPOUND -> compound(in, depth);
            default -> throw new IOException("structure file tag " + type + " is not read");
        };
    }

    private static List<?> list(Object value, String what) throws IOException {
        if (!(value instanceof List<?> list)) {
            throw new IOException(what + " must be a list");
        }
        return list;
    }

    private static List<Integer> ints(Object value, String what) throws IOException {
        List<Integer> values = new ArrayList<>();
        for (Object item : list(value, what)) {
            if (!(item instanceof Integer number)) {
                throw new IOException(what + " must be whole numbers");
            }
            values.add(number);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String what) throws IOException {
        if (!(value instanceof Map<?, ?>)) {
            throw new IOException(what + " must be a compound");
        }
        return (Map<String, Object>) value;
    }

    private static String string(Object value, String what) throws IOException {
        if (!(value instanceof String text)) {
            throw new IOException(what + " must be text");
        }
        return text;
    }
}
