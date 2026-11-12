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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Writes the bundled lobby structure from its readable spec, so the binary file is never
 * edited by hand. Run it with {@code ./gradlew generateLobbyBuild}; {@code LobbyBuildTest}
 * checks that the committed file is what it writes.
 *
 * <p>The output is a vanilla structure file: gzip-compressed NBT holding the data version,
 * the size, a palette of block states and one entry per non-air block. Air is not stored,
 * so placing the structure leaves whatever already stands in its empty cells.
 */
public final class LobbyBuildGenerator {

    private static final Pattern BLOCK = Pattern.compile("[a-z0-9_]+");

    private static final byte END = 0;
    private static final byte INT = 3;
    private static final byte STRING = 8;
    private static final byte LIST = 9;
    private static final byte COMPOUND = 10;

    private LobbyBuildGenerator() { }

    /** {@code <spec.yml> <out.nbt>} */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LobbyBuildGenerator <spec.yml> <out.nbt>");
        }
        byte[] nbt;
        try (Reader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            nbt = generate(reader);
        }
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            out.write(nbt);
        }
    }

    /** The uncompressed NBT of the structure the spec describes. */
    public static byte[] generate(Reader reader) throws IOException {
        YamlConfiguration spec = new YamlConfiguration();
        try {
            spec.load(reader);
        } catch (InvalidConfigurationException e) {
            throw new IOException(e.getMessage(), e);
        }
        // Validates the marks, so a spec the plugin cannot read is never generated.
        LobbyLayout.of(spec);
        int dataVersion = spec.getInt("data-version", -1);
        if (dataVersion < 1) {
            throw new IllegalArgumentException("data-version must be a positive whole number");
        }
        int[] size = LobbyLayout.triple(spec, "size");
        String[][][] cells = new String[size[0]][size[1]][size[2]];
        for (Map<?, ?> box : spec.getMapList("blocks")) {
            String block = String.valueOf(box.get("block")).toLowerCase(Locale.ROOT);
            if (!BLOCK.matcher(block).matches()) {
                throw new IllegalArgumentException("not a block id: " + block);
            }
            YamlConfiguration corners = new YamlConfiguration();
            corners.set("from", box.get("from"));
            corners.set("to", box.get("to"));
            int[] from = LobbyLayout.triple(corners, "from");
            int[] to = LobbyLayout.triple(corners, "to");
            for (int axis = 0; axis < 3; axis++) {
                if (Math.min(from[axis], to[axis]) < 0 || Math.max(from[axis], to[axis]) >= size[axis]) {
                    throw new IllegalArgumentException("box of " + block + " reaches outside the size");
                }
            }
            for (int x = Math.min(from[0], to[0]); x <= Math.max(from[0], to[0]); x++) {
                for (int y = Math.min(from[1], to[1]); y <= Math.max(from[1], to[1]); y++) {
                    for (int z = Math.min(from[2], to[2]); z <= Math.max(from[2], to[2]); z++) {
                        cells[x][y][z] = "minecraft:" + block;
                    }
                }
            }
        }
        Map<String, Integer> palette = new LinkedHashMap<>();
        List<int[]> blocks = new ArrayList<>();
        // The order vanilla writes: by height, then north to south, then west to east.
        for (int y = 0; y < size[1]; y++) {
            for (int z = 0; z < size[2]; z++) {
                for (int x = 0; x < size[0]; x++) {
                    String block = cells[x][y][z];
                    if (block != null) {
                        int state = palette.computeIfAbsent(block, ignored -> palette.size());
                        blocks.add(new int[] {x, y, z, state});
                    }
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
        out.writeInt(palette.size());
        for (String block : palette.keySet()) {
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
        name(out, LIST, "entities");
        out.writeByte(END);
        out.writeInt(0);
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
