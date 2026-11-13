package com.ninja6.ninjaarena.map;

/** Cell writers on a fake clock, for tests. */
public final class FakeWriters {

    private FakeWriters() {
    }

    /**
     * A writer that visits {@code blocksPerTick} blocks a tick: each block visited moves a
     * fake clock on one nanosecond, and the budget is that many nanoseconds.
     */
    public static BudgetedCellWriter perTick(BlockAccess blocks, long blocksPerTick) {
        BudgetedCellWriter[] writer = new BudgetedCellWriter[1];
        writer[0] = new BudgetedCellWriter(blocks, () -> blocksPerTick, () -> writer[0].visited());
        return writer[0];
    }
}
