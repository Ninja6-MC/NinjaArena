package com.ninja6.ninjaarena.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.GridCell;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlotCellsTest {

    private static final ArenaGrid GRID = new ArenaGrid(256, 2048, -64, 320);

    private final Set<GridCell> handBuilt = new HashSet<>();
    private final List<String> log = new ArrayList<>();

    private SlotCells cells(int count) {
        SlotCells cells = new SlotCells(GRID, () -> handBuilt, log::add);
        cells.resize(count, cell -> false);
        return cells;
    }

    private static List<GridCell> farthestFirst() {
        return new ArrayList<>(GRID.cellsNearestFirst()).reversed();
    }

    @Test
    void cellsAreTakenFarthestFromTheOriginFirstAndAreTheSameEveryTime() {
        List<GridCell> expected = farthestFirst().subList(0, 4);
        assertEquals(expected, cells(4).cells());
        assertEquals(expected, cells(4).cells(), "a restart with the same config reserves the same cells");
        assertFalse(expected.contains(GRID.cellsNearestFirst().getFirst()),
                "the cell arena create fills first is not reserved");
        assertTrue(log.isEmpty());
    }

    @Test
    void aCellHoldingAHandBuiltArenaIsPassedOverAndLogged() {
        List<GridCell> order = farthestFirst();
        handBuilt.add(order.get(1));
        SlotCells cells = cells(3);
        assertEquals(List.of(order.get(0), order.get(2), order.get(3)), cells.cells());
        assertFalse(cells.isReserved(order.get(1)));
        assertEquals(1, log.size());
        assertTrue(log.getFirst().contains(order.get(1).describe()), log.getFirst());
    }

    @Test
    void aReloadWithTheSameCountMovesNothingAndAHigherOneAddsAfter() {
        SlotCells cells = cells(2);
        List<GridCell> before = cells.cells();
        // An arena built since in a cell that would come next changes nothing already reserved.
        handBuilt.add(farthestFirst().get(2));
        cells.resize(2, cell -> false);
        assertEquals(before, cells.cells());
        cells.resize(3, cell -> false);
        assertEquals(List.of(before.get(0), before.get(1), farthestFirst().get(3)), cells.cells());
    }

    @Test
    void aLowerCountNeverGivesUpACellInUseUntilItIsFree() {
        SlotCells cells = cells(3);
        List<GridCell> before = cells.cells();
        Set<GridCell> used = new HashSet<>(Set.of(before.get(2)));
        cells.resize(1, used::contains);
        assertEquals(List.of(before.get(0), before.get(2)), cells.cells());
        assertTrue(cells.isReserved(before.get(2)));
        cells.trim(used::contains);
        assertEquals(List.of(before.get(0), before.get(2)), cells.cells(), "still in use");
        used.clear();
        cells.trim(used::contains);
        assertEquals(List.of(before.get(0)), cells.cells());
        cells.resize(3, used::contains);
        assertEquals(before, cells.cells(), "raised again, the same cells come back in the same order");
    }

    @Test
    void aGridWithTooFewFreeCellsReservesWhatItHasAndSaysSo() {
        ArenaGrid small = new ArenaGrid(256, 512, -64, 320);
        SlotCells cells = new SlotCells(small, () -> Set.of(new GridCell(0, 0)), log::add);
        cells.resize(5, cell -> false);
        assertEquals(3, cells.cells().size());
        assertFalse(cells.isReserved(new GridCell(0, 0)));
        assertTrue(log.getLast().contains("only 3"), log.getLast());
    }
}
