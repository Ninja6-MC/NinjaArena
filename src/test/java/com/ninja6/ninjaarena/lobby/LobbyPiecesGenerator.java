package com.ninja6.ninjaarena.lobby;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Writes the generated lobby's piece structures from their readable spec, so no binary
 * file is edited by hand. Run it with {@code ./gradlew generateLobbyPieces};
 * {@code LobbyPiecesTest} checks that the committed files are what it writes.
 *
 * <p>Each piece becomes a vanilla structure file, as {@link LobbyBuildGenerator} writes
 * the obsidian build: the data version, the size, a palette of block states, one entry per
 * block that is not left alone, and the piece's cushions as entities.
 */
public final class LobbyPiecesGenerator {

    private static final Pattern BLOCK = Pattern.compile("[a-z0-9_]+");
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]+");
    private static final Pattern COLOR = Pattern.compile("[a-z_]+");

    private static final byte END = 0;
    private static final byte INT = 3;
    private static final byte FLOAT = 5;
    private static final byte DOUBLE = 6;
    private static final byte STRING = 8;
    private static final byte LIST = 9;
    private static final byte COMPOUND = 10;

    private LobbyPiecesGenerator() { }

    /** {@code <spec.yml> <out dir>}; every other .nbt in the folder is removed. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LobbyPiecesGenerator <spec.yml> <out dir>");
        }
        Map<String, byte[]> pieces;
        try (Reader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            pieces = generate(reader);
        }
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        try (Stream<Path> old = Files.list(out)) {
            for (Path file : old.filter(file -> file.getFileName().toString().endsWith(".nbt")).toList()) {
                Files.delete(file);
            }
        }
        for (Map.Entry<String, byte[]> piece : pieces.entrySet()) {
            try (OutputStream file = new GZIPOutputStream(Files.newOutputStream(out.resolve(piece.getKey() + ".nbt")))) {
                file.write(piece.getValue());
            }
        }
    }

    /** The uncompressed NBT of every piece the spec describes, by name in file order. */
    public static Map<String, byte[]> generate(Reader reader) throws IOException {
        YamlConfiguration spec = new YamlConfiguration();
        try {
            spec.load(reader);
        } catch (InvalidConfigurationException e) {
            throw new IOException(e.getMessage(), e);
        }
        int dataVersion = spec.getInt("data-version", -1);
        if (dataVersion < 1) {
            throw new IllegalArgumentException("data-version must be a positive whole number");
        }
        ConfigurationSection paletteSection = spec.getConfigurationSection("palette");
        ConfigurationSection piecesSection = spec.getConfigurationSection("pieces");
        if (paletteSection == null || piecesSection == null) {
            throw new IllegalArgumentException("palette and pieces must be sections");
        }
        Map<Character, String> palette = new LinkedHashMap<>();
        for (String letter : paletteSection.getKeys(false)) {
            String block = paletteSection.getString(letter, "");
            if (letter.length() != 1 || letter.equals(".") || !BLOCK.matcher(block).matches()) {
                throw new IllegalArgumentException("palette " + letter + " must be one letter for a block id");
            }
            palette.put(letter.charAt(0), "minecraft:" + block);
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String name : piecesSection.getKeys(false)) {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("not a piece name: " + name);
            }
            out.put(name, piece(name, piecesSection.getConfigurationSection(name), palette, dataVersion));
        }
        return out;
    }

    private static byte[] piece(String name, ConfigurationSection piece, Map<Character, String> palette,
            int dataVersion) throws IOException {
        if (piece == null) {
            throw new IllegalArgumentException(name + " must be a section");
        }
        int[] size = LobbyLayout.triple(piece, "size");
        List<?> layers = piece.getList("layers");
        if (layers == null || layers.size() != size[1]) {
            throw new IllegalArgumentException(name + " must have " + size[1] + " layers");
        }
        Map<String, Integer> states = new LinkedHashMap<>();
        List<int[]> blocks = new ArrayList<>();
        // The order vanilla writes: by height, then north to south, then west to east.
        for (int y = 0; y < size[1]; y++) {
            if (!(layers.get(y) instanceof List<?> rows) || rows.size() != size[2]) {
                throw new IllegalArgumentException(name + " layer " + y + " must have " + size[2] + " rows");
            }
            for (int z = 0; z < size[2]; z++) {
                if (!(rows.get(z) instanceof String row) || row.length() != size[0]) {
                    throw new IllegalArgumentException(name + " layer " + y + " row " + z + " must be " + size[0]
                            + " letters");
                }
                for (int x = 0; x < size[0]; x++) {
                    char letter = row.charAt(x);
                    if (letter == '.') {
                        continue;
                    }
                    String block = palette.get(letter);
                    if (block == null) {
                        throw new IllegalArgumentException(name + " uses " + letter + ", which is not in the palette");
                    }
                    int state = states.computeIfAbsent(block, ignored -> states.size());
                    blocks.add(new int[] {x, y, z, state});
                }
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(COMPOUND);
        out.writeUTF("");
        name(out, INT, "DataVersion");
        out.writeInt(dataVersion);
        name(out, LIST, "size");
        ints(out, size);
        name(out, LIST, "palette");
        out.writeByte(COMPOUND);
        out.writeInt(states.size());
        for (String block : states.keySet()) {
            name(out, STRING, "Name");
            out.writeUTF(block);
            out.writeByte(END);
        }
        name(out, LIST, "blocks");
        out.writeByte(COMPOUND);
        out.writeInt(blocks.size());
        for (int[] block : blocks) {
            name(out, LIST, "pos");
            ints(out, new int[] {block[0], block[1], block[2]});
            name(out, INT, "state");
            out.writeInt(block[3]);
            out.writeByte(END);
        }
        List<Map<?, ?>> entities = piece.getMapList("entities");
        name(out, LIST, "entities");
        out.writeByte(entities.isEmpty() ? END : COMPOUND);
        out.writeInt(entities.size());
        for (Map<?, ?> entity : entities) {
            if (!"cushion".equals(entity.get("entity"))) {
                throw new IllegalArgumentException(name + " has an entity other than a cushion");
            }
            YamlConfiguration fields = new YamlConfiguration();
            entity.forEach((key, value) -> fields.set(String.valueOf(key), value));
            List<?> at = fields.getList("at");
            if (at == null || at.size() != 3 || !at.stream().allMatch(Number.class::isInstance)) {
                throw new IllegalArgumentException(name + " has a cushion with no position");
            }
            double[] pos = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                pos[axis] = ((Number) at.get(axis)).doubleValue();
                if (pos[axis] < 0 || pos[axis] >= size[axis]) {
                    throw new IllegalArgumentException(name + " has a cushion outside its size");
                }
            }
            String color = fields.getString("color", "");
            if (!COLOR.matcher(color).matches()) {
                throw new IllegalArgumentException(name + " has a cushion with no colour");
            }
            name(out, LIST, "pos");
            out.writeByte(DOUBLE);
            out.writeInt(3);
            for (double value : pos) {
                out.writeDouble(value);
            }
            name(out, LIST, "blockPos");
            ints(out, new int[] {(int) Math.floor(pos[0]), (int) Math.floor(pos[1]), (int) Math.floor(pos[2])});
            name(out, COMPOUND, "nbt");
            name(out, STRING, "id");
            out.writeUTF("minecraft:cushion");
            name(out, STRING, "color");
            out.writeUTF(color);
            name(out, LIST, "Rotation");
            out.writeByte(FLOAT);
            out.writeInt(2);
            out.writeFloat((float) fields.getDouble("yaw"));
            out.writeFloat(0f);
            out.writeByte(END);
            out.writeByte(END);
        }
        out.writeByte(END);
        out.flush();
        return bytes.toByteArray();
    }

    private static void name(DataOutputStream out, byte type, String name) throws IOException {
        out.writeByte(type);
        out.writeUTF(name);
    }

    private static void ints(DataOutputStream out, int[] values) throws IOException {
        out.writeByte(INT);
        out.writeInt(values.length);
        for (int value : values) {
            out.writeInt(value);
        }
    }
}
