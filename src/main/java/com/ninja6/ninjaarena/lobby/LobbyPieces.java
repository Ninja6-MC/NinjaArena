package com.ninja6.ninjaarena.lobby;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The pieces {@link GeneratedLobby} assembles, read from the structure files in
 * {@value #FOLDER} that {@value #SPEC} describes.
 *
 * @param floor   the floor block wherever no other piece is
 * @param wall    one column of the edge
 * @param centre  the middle square with the emblem: stone under it, then the wool layer
 * @param pad     one room pad in its frame
 * @param corners the corner squares by {@code nw}, {@code ne}, {@code sw} and {@code se}
 * @param trees   the tree variants for the edge posts, at least one
 */
public record LobbyPieces(LobbyPiece floor, LobbyPiece wall, LobbyPiece centre, LobbyPiece pad,
        Map<String, LobbyPiece> corners, List<LobbyPiece> trees) {

    /** The readable spec inside the plugin JAR. */
    public static final String SPEC = "lobby/generated-lobby.yml";
    /** Where the structures generated from {@link #SPEC} are. */
    public static final String FOLDER = "lobby/pieces/";
    /** Tree variants are {@code tree-01}, {@code tree-02} and so on, without gaps. */
    public static final int MAX_TREES = 99;
    public static final List<String> CORNERS = List.of("nw", "ne", "sw", "se");

    public LobbyPieces {
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(wall, "wall");
        Objects.requireNonNull(centre, "centre");
        Objects.requireNonNull(pad, "pad");
        corners = Map.copyOf(corners);
        trees = List.copyOf(trees);
        if (floor.blocks().size() != 1) {
            throw new IllegalArgumentException("the floor must be one block");
        }
        if (trees.isEmpty()) {
            throw new IllegalArgumentException("there must be at least one tree variant");
        }
        if (!corners.keySet().containsAll(CORNERS)) {
            throw new IllegalArgumentException("every corner must have a piece");
        }
        if (centre.sizeX() != GeneratedLobby.EMBLEM || centre.sizeZ() != GeneratedLobby.EMBLEM
                || centre.sizeY() != 2) {
            throw new IllegalArgumentException("the centre must be " + GeneratedLobby.EMBLEM + " x 2 x "
                    + GeneratedLobby.EMBLEM);
        }
        if (pad.sizeX() != GeneratedLobby.BAND || pad.sizeZ() != GeneratedLobby.BAND) {
            throw new IllegalArgumentException("a pad must be " + GeneratedLobby.BAND + " square");
        }
        for (LobbyPiece corner : corners.values()) {
            if (corner.sizeX() != GeneratedLobby.BAND || corner.sizeZ() != GeneratedLobby.BAND) {
                throw new IllegalArgumentException("a corner must be " + GeneratedLobby.BAND + " square");
            }
        }
        if (wall.sizeY() != GeneratedLobby.WALL_HEIGHT) {
            throw new IllegalArgumentException("the wall must be " + GeneratedLobby.WALL_HEIGHT + " high");
        }
        for (LobbyPiece tree : trees) {
            if (tree.sizeX() != 2 * GeneratedLobby.TREE_REACH + 1 || tree.sizeZ() != 2 * GeneratedLobby.TREE_REACH + 1
                    || tree.sizeY() > GeneratedLobby.WALL_HEIGHT) {
                throw new IllegalArgumentException(tree.name() + " does not fit an edge post");
            }
        }
    }

    /** The pieces bundled in the plugin JAR. */
    public static LobbyPieces bundled() throws IOException {
        ClassLoader loader = LobbyPieces.class.getClassLoader();
        List<LobbyPiece> trees = new ArrayList<>();
        for (int i = 1; i <= MAX_TREES; i++) {
            String name = String.format("tree-%02d", i);
            if (loader.getResource(FOLDER + name + ".nbt") == null) {
                break;
            }
            trees.add(read(loader, name));
        }
        try {
            return new LobbyPieces(read(loader, "floor"), read(loader, "wall"), read(loader, "centre"),
                    read(loader, "pad"), Map.of("nw", read(loader, "corner-nw"), "ne", read(loader, "corner-ne"),
                            "sw", read(loader, "corner-sw"), "se", read(loader, "corner-se")), trees);
        } catch (IllegalArgumentException e) {
            throw new IOException("the bundled lobby pieces do not fit together: " + e.getMessage(), e);
        }
    }

    private static LobbyPiece read(ClassLoader loader, String name) throws IOException {
        try (InputStream in = loader.getResourceAsStream(FOLDER + name + ".nbt")) {
            if (in == null) {
                throw new IOException(FOLDER + name + ".nbt is missing from the plugin JAR");
            }
            return LobbyPiece.read(name, in);
        }
    }

    /** The floor block. */
    public String floorBlock() {
        return floor.blocks().getFirst().state();
    }
}
