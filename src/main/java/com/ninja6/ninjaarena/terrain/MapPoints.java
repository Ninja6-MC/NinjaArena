package com.ninja6.ninjaarena.terrain;

import com.ninja6.ninjaarena.arena.ArenaPoint;
import java.util.List;

/**
 * Where players and enemies appear on a generated map, in the {@link ArenaLayout} map
 * frame: block centres, feet height, and a Minecraft yaw that assumes map x and z run
 * along world x and z. Placement adds the map's world origin. Generated maps set no
 * waiting-room point.
 *
 * @param teamSpawns  one per team, team 1 first, each standing on its spawn pad
 * @param enemySpawns {@link MovementSolver#ENEMY_SPAWNS_PER_SIDE} per team at most, in team
 *                    order
 */
public record MapPoints(List<Point> teamSpawns, List<Point> enemySpawns) {

    public MapPoints {
        teamSpawns = List.copyOf(teamSpawns);
        enemySpawns = List.copyOf(enemySpawns);
    }

    /** A position in the map frame with the yaw a player or mob faces. */
    public record Point(double x, double y, double z, float yaw) {

        /** The block the feet are in. */
        public ArenaLayout.Position block() {
            return new ArenaLayout.Position((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }

        /** This point in the world once the map's {@code (0, 0, 0)} is placed at the origin. */
        public ArenaPoint toArenaPoint(int originX, int originY, int originZ) {
            return new ArenaPoint(originX + x, originY + y, originZ + z, yaw, 0);
        }
    }
}
