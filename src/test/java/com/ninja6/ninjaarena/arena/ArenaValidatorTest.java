package com.ninja6.ninjaarena.arena;

import static com.ninja6.ninjaarena.arena.Arenas.GRID;
import static com.ninja6.ninjaarena.arena.Arenas.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArenaValidatorTest {

    @Test
    void completeArenaHasNoProblems() {
        assertEquals(List.of(), ArenaValidator.problems(Arenas.complete("dojo"), GRID));
    }

    @Test
    void newArenaListsEverythingMissing() {
        List<String> problems = ArenaValidator.problems(Arena.create("dojo", new GridCell(0, 0)), GRID);
        assertEquals(List.of(
                "Bounds are not set.",
                "The waiting room is not set.",
                "Needs 2 to 4 team spawns, has 0.",
                "Needs at least one enemy spawn point."), problems);
    }

    @Test
    void oneTeamIsNotEnough() {
        Arena arena = Arenas.complete("dojo").withoutLastTeamSpawn();
        assertEquals(List.of("Needs 2 to 4 team spawns, has 1."), ArenaValidator.problems(arena, GRID));
    }

    @Test
    void fourTeamsIsTheMaximum() {
        Arena arena = Arenas.complete("dojo")
                .withTeamSpawn(3, point(30, 64, 30))
                .withTeamSpawn(4, point(40, 64, 40));
        assertEquals(List.of(), ArenaValidator.problems(arena, GRID));
        assertThrows(IllegalArgumentException.class, () -> arena.withTeamSpawn(5, point(45, 64, 45)));
    }

    @Test
    void teamNumbersStayContiguous() {
        Arena arena = Arena.create("dojo", new GridCell(0, 0));
        assertThrows(IllegalArgumentException.class, () -> arena.withTeamSpawn(2, point(1, 1, 1)));
        Arena replaced = arena.withTeamSpawn(1, point(1, 1, 1)).withTeamSpawn(1, point(2, 2, 2));
        assertEquals(List.of(point(2, 2, 2)), replaced.teamSpawns());
    }

    @Test
    void everyPointOutsideTheBoundsIsReported() {
        Arena arena = Arenas.complete("dojo")
                .withWaitingRoom(point(5, 64, 50))
                .withTeamSpawn(2, point(50, 101, 50))
                .withEnemySpawn(point(90.9, 64, 91));
        assertEquals(List.of(
                "The waiting room is outside the bounds.",
                "Team 2 spawn is outside the bounds.",
                "Enemy spawn 3 is outside the bounds."), ArenaValidator.problems(arena, GRID));
    }

    @Test
    void pointOnTheLastBlockOfTheBoundsIsInside() {
        Arena arena = Arenas.complete("dojo").withEnemySpawn(point(90.99, 100.5, 10));
        assertEquals(List.of(), ArenaValidator.problems(arena, GRID));
    }

    @Test
    void boundsMustStayInTheCell() {
        Arena arena = Arenas.complete("dojo").withBounds(new ArenaBounds(10, 60, 10, 256, 100, 90));
        List<String> problems = ArenaValidator.problems(arena, GRID);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("Bounds 10, 60, 10 to 256, 100, 90 leave cell 0, 0"), problems.get(0));
    }

    @Test
    void cellOutsideTheBorderIsReported() {
        Arena arena = new Arena("far", new GridCell(10, 0), null, null, List.of(), List.of());
        assertEquals("Cell 10, 0 is not inside the world border.", ArenaValidator.problems(arena, GRID).get(0));
    }

    @Test
    void namesAreRestricted() {
        assertTrue(Arena.isValidName("dojo_2-b"));
        assertThrows(IllegalArgumentException.class, () -> Arena.create("Dojo", new GridCell(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> Arena.create("a b", new GridCell(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> Arena.create("x".repeat(33), new GridCell(0, 0)));
    }
}
