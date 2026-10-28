package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.director.SpawnPlacement.Refusal;
import com.ninja6.ninjaarena.director.SpawnPlacement.Refused;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.Test;

class SpawnPlacementTest {

    /** Yaw looking towards +x, -x and +z. */
    private static final float EAST = -90;
    private static final float WEST = 90;
    private static final float SOUTH = 0;

    private final Map<List<Integer>, List<BoundingBox>> blocks = new HashMap<>();
    private final List<Player> players = new ArrayList<>();
    private final Set<List<Integer>> unloaded = new HashSet<>();
    private final World world = proxy(World.class, (name, args) -> switch (name) {
        case "getMinHeight" -> -64;
        case "getMaxHeight" -> 320;
        case "isChunkLoaded" -> !unloaded.contains(List.of((int) args[0], (int) args[1]));
        case "getBlockAt" -> block((int) args[0], (int) args[1], (int) args[2]);
        default -> throw new UnsupportedOperationException(name);
    });

    @Test
    void rotatesAcrossRegisteredPointsAndPreservesFacing() {
        SpawnPlacement placement = placement(point(10.5), point(20.5), point(30.5));
        for (double x : new double[] {10.5, 20.5, 30.5, 10.5}) {
            Location selected = placement.next(world, players, 0.6, 1.8).orElseThrow();
            assertEquals(x, selected.getX());
            assertEquals(64, selected.getY());
            assertEquals(0.5, selected.getZ());
            assertEquals(90, selected.getYaw());
            assertEquals(15, selected.getPitch());
        }
    }

    @Test
    void rejectsNearbyPlayersIncludingEightBlockBoundary() {
        players.add(player(2.5, false));
        SpawnPlacement placement = placement(point(10.49), point(10.5), point(10.51));
        assertEquals(10.51, placement.next(world, players, 0.6, 1.8).orElseThrow().getX());
    }

    @Test
    void aPlayerSeeingOnlyTheUpperBodyRejectsThePoint() {
        players.add(proxy(Player.class, (name, args) -> switch (name) {
            case "getLocation" -> new Location(world, 40, 64, 0.5);
            case "getEyeLocation" -> new Location(world, 40, 65.62, 0.5, WEST, 0);
            case "hasLineOfSight" -> ((Location) args[0]).getY() > 65;
            default -> throw new UnsupportedOperationException(name);
        }));
        assertTrue(placement(point(10.5)).next(world, players, 0.6, 1.8).isEmpty());
    }

    @Test
    void anyPlayerWithLineOfSightRejectsEvenADistantPoint() {
        players.add(player(-100, false));
        players.add(player(100, true));
        assertTrue(placement(point(10.5)).next(world, players, 0.6, 1.8).isEmpty());
    }

    @Test
    void rechecksPlayersAndRecoversWhenAllPointsWereBlocked() {
        SpawnPlacement placement = placement(point(10.5), point(20.5));
        players.add(player(10.5, true));
        assertTrue(placement.next(world, players, 0.6, 1.8).isEmpty());
        players.clear();
        assertEquals(10.5, placement.next(world, players, 0.6, 1.8).orElseThrow().getX());
        players.add(player(20.5, false));
        assertEquals(10.5, placement.next(world, players, 0.6, 1.8).orElseThrow().getX());
        players.clear();
        assertEquals(20.5, placement.next(world, players, 0.6, 1.8).orElseThrow().getX());
    }

    @Test
    void rejectsFeetAndHeadCollisionsAndRechecksBlocks() {
        SpawnPlacement placement = placement(point(10.5), point(20.5), point(30.5));
        solid(10, 64, 0, 1);
        solid(20, 65, 0, 1);
        assertEquals(30.5, placement.next(world, players, 0.6, 1.8).orElseThrow().getX());
        blocks.clear();
        assertEquals(10.5, placement.next(world, players, 0.6, 1.8).orElseThrow().getX());
    }

    @Test
    void checksFullWidthAndHeightRatherThanOnlyFeetColumn() {
        solid(11, 64, 0, 1);
        assertTrue(placement(point(10.9)).next(world, players, 0.6, 1.8).isEmpty());
        blocks.clear();
        solid(10, 66, 0, 1);
        assertTrue(placement(point(10.5)).next(world, players, 0.6, 2.4).isEmpty());
        assertTrue(placement(point(10.5)).next(world, players, 0.6, 1.8).isPresent());
    }

    @Test
    void permitsTouchingGroundButRejectsProtrudingFenceBelow() {
        solid(10, 63, 0, 1);
        SpawnPlacement placement = placement(point(10.5));
        assertTrue(placement.next(world, players, 0.6, 1.8).isPresent());
        solid(10, 63, 0, 1.5);
        assertTrue(placement.next(world, players, 0.6, 1.8).isEmpty());
    }

    @Test
    void usesPartialCollisionShapesAtFractionalAndNegativeCoordinates() {
        solid(-1, 64, 0, 0.5);
        SpawnPlacement onSlab = placement(new ArenaPoint(-0.5, 64.5, 0.5, 0, 0));
        assertTrue(onSlab.next(world, players, 0.6, 1.8).isPresent());
        SpawnPlacement inSlab = placement(new ArenaPoint(-0.5, 64.4, 0.5, 0, 0));
        assertTrue(inSlab.next(world, players, 0.6, 1.8).isEmpty());
    }

    @Test
    void skipsUnloadedChunksAndWorldHeightViolations() {
        unloaded.add(List.of(1, 0));
        assertTrue(placement(point(15.9)).next(world, players, 0.6, 1.8).isEmpty());
        assertTrue(placement(new ArenaPoint(0.5, 319, 0.5, 0, 0)).next(world, players, 0.6, 1.8).isEmpty());
        assertTrue(placement(new ArenaPoint(0.5, -65, 0.5, 0, 0)).next(world, players, 0.6, 1.8).isEmpty());
    }

    @Test
    void emptyRegistrationHasNoFallbackAndDimensionsMustBePositiveAndFinite() {
        SpawnPlacement placement = placement();
        assertTrue(placement.next(world, players, 0.6, 1.8).isEmpty());
        for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> placement.next(world, players, invalid, 1.8));
            assertThrows(IllegalArgumentException.class, () -> placement.next(world, players, 0.6, invalid));
        }
    }

    @Test
    void aSinglePointBehindAPlayerWithASightLineIsUsed() {
        players.add(player(30.5, EAST, true));
        SpawnPlacement placement = placement(point(10.5));
        assertEquals(10.5, placement.next(world, players, 0.6, 1.8).orElseThrow().getX());
        assertEquals(List.of(), placement.refusals());
    }

    @Test
    void aPlayerFacingAPointInSightRefusesItAsInView() {
        players.add(player(30.5, WEST, true));
        SpawnPlacement placement = placement(point(10.5));
        assertTrue(placement.next(world, players, 0.6, 1.8).isEmpty());
        assertEquals(List.of(new Refused(1, Refusal.IN_VIEW)), placement.refusals());
    }

    @Test
    void coverHidesAPointThePlayerFaces() {
        players.add(player(30.5, WEST, false));
        assertTrue(placement(point(10.5)).next(world, players, 0.6, 1.8).isPresent());
    }

    @Test
    void lookingAwayDoesNotHideAPointWithinEightBlocks() {
        players.add(player(16.5, EAST, false));
        SpawnPlacement placement = placement(point(10.5));
        assertTrue(placement.next(world, players, 0.6, 1.8).isEmpty());
        assertEquals(List.of(new Refused(1, Refusal.TOO_CLOSE)), placement.refusals());
    }

    @Test
    void theViewAngleIsMeasuredFromTheLookDirection() {
        // Looking along +z, the point lies 90 degrees off to the side.
        players.add(player(-9.5, SOUTH, true));
        assertTrue(placement(SpawnSettings.DEFAULTS, point(10.5)).next(world, players, 0.6, 1.8).isEmpty(),
                "inside the default 100 degrees");
        assertTrue(placement(new SpawnSettings(80, false), point(10.5)).next(world, players, 0.6, 1.8).isPresent(),
                "outside 80 degrees");
        players.clear();
        players.add(player(30.5, EAST, true));
        assertTrue(placement(new SpawnSettings(180, false), point(10.5)).next(world, players, 0.6, 1.8).isEmpty(),
                "180 leaves only cover");
    }

    @Test
    void everyPlayerMustHaveThePointOutOfViewOrBehindCover() {
        players.add(player(30.5, EAST, true));
        players.add(player(-9.5, EAST, false));
        assertTrue(placement(point(10.5)).next(world, players, 0.6, 1.8).isPresent());
        players.add(player(-20.5, EAST, true));
        SpawnPlacement placement = placement(point(10.5));
        assertTrue(placement.next(world, players, 0.6, 1.8).isEmpty());
        assertEquals(List.of(new Refused(1, Refusal.IN_VIEW)), placement.refusals());
    }

    @Test
    void reportsTheFirstCheckThatRefusedEachPoint() {
        unloaded.add(List.of(-2, 0));
        solid(40, 64, 0, 1);
        players.add(player(60.5, WEST, true));
        SpawnPlacement placement = placement(point(-20.5), point(40.5), point(55.5), point(10.5));
        assertTrue(placement.next(world, players, 0.6, 1.8).isEmpty());
        assertEquals(List.of(new Refused(1, Refusal.CHUNK_NOT_LOADED), new Refused(2, Refusal.BLOCKED),
                new Refused(3, Refusal.TOO_CLOSE), new Refused(4, Refusal.IN_VIEW)), placement.refusals());
    }

    @Test
    void viewAngleSettingIsClampedAndReadFromTheBundledConfig() throws Exception {
        assertEquals(new SpawnSettings(0, false), new SpawnSettings(-5, false));
        assertEquals(new SpawnSettings(180, true), new SpawnSettings(400, true));
        assertEquals(SpawnSettings.DEFAULTS, new SpawnSettings(Double.NaN, false));
        assertEquals(SpawnSettings.DEFAULTS, SpawnSettings.from(null));
        assertEquals(SpawnSettings.DEFAULTS, SpawnSettings.from(new YamlConfiguration()));
        var custom = new YamlConfiguration();
        custom.set("spawn.view-angle", 120.0);
        custom.set("debug", true);
        assertEquals(new SpawnSettings(120, true), SpawnSettings.from(custom));
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals(new SpawnSettings(100, false), SpawnSettings.DEFAULTS);
            assertEquals(SpawnSettings.DEFAULTS, SpawnSettings.from(config.getConfigurationSection("director")));
        }
    }

    private SpawnPlacement placement(ArenaPoint... points) {
        return placement(SpawnSettings.DEFAULTS, points);
    }

    private SpawnPlacement placement(SpawnSettings settings, ArenaPoint... points) {
        return new SpawnPlacement(new Arena("dojo", new GridCell(0, 0), null, null, List.of(), List.of(points)),
                settings);
    }

    private ArenaPoint point(double x) {
        return new ArenaPoint(x, 64, 0.5, 90, 15);
    }

    /** A player at x looking along the x axis towards the points near x = 10.5. */
    private Player player(double x, boolean visible) {
        return player(x, x > 10.5 ? WEST : EAST, visible);
    }

    private Player player(double x, float yaw, boolean visible) {
        return player(x, 0.5, yaw, visible);
    }

    private Player player(double x, double z, float yaw, boolean visible) {
        return proxy(Player.class, (name, args) -> switch (name) {
            case "getLocation" -> new Location(world, x, 64, z);
            case "getEyeLocation" -> new Location(world, x, 65.62, z, yaw, 0);
            case "hasLineOfSight" -> {
                assertTrue(args[0] instanceof Location);
                yield visible;
            }
            default -> throw new UnsupportedOperationException(name);
        });
    }

    private void solid(int x, int y, int z, double height) {
        blocks.put(List.of(x, y, z), List.of(new BoundingBox(0, 0, 0, 1, height, 1)));
    }

    private Block block(int x, int y, int z) {
        return proxy(Block.class, (name, args) -> {
            if (name.equals("getCollisionShape")) {
                return proxy(VoxelShape.class, (method, arguments) -> {
                    if (method.equals("getBoundingBoxes")) {
                        return blocks.getOrDefault(List.of(x, y, z), List.of());
                    }
                    throw new UnsupportedOperationException(method);
                });
            }
            throw new UnsupportedOperationException(name);
        });
    }

    private interface Call {
        Object invoke(String name, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (p, method, args) -> call.invoke(method.getName(), args)));
    }
}
