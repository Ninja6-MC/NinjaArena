package com.ninja6.ninjaarena.terrain;

/**
 * How far a player can move between standing cells, for {@link MovementSolver}. The
 * defaults are cautious vanilla figures, so a route the solver finds is one an ordinary
 * player can follow without a perfect sprint jump.
 *
 * <ul>
 * <li>A <em>walk</em> moves to the next column at the same height.</li>
 * <li>A <em>climb</em> moves to the next column up to {@code jumpHeight} blocks higher.</li>
 * <li>A <em>drop</em> walks off an edge into the next column and lands at most
 * {@code safeFall} blocks lower.</li>
 * <li>A <em>jump</em> clears a gap of 1 to {@code maxGap} columns in a straight line and lands
 * from {@code safeFall} blocks lower to {@code jumpHeight} blocks higher; a rising jump
 * clears at most {@code maxRisingGap} columns.</li>
 * </ul>
 *
 * @param jumpHeight   most blocks a climb or jump rises, at least 1
 * @param maxGap       widest gap a level or falling jump clears, in columns, at least 1
 * @param maxRisingGap widest gap a rising jump clears, from 0 to {@code maxGap}
 * @param safeFall     most blocks a drop or jump may land below its start, at least 1
 */
public record MovementModel(int jumpHeight, int maxGap, int maxRisingGap, int safeFall) {

    /** Jump up 1, clear 3 columns level or 2 rising, and drop 3 without fall damage. */
    public static final MovementModel DEFAULT = new MovementModel(1, 3, 2, 3);

    public MovementModel {
        if (jumpHeight < 1 || jumpHeight > 4) {
            throw new IllegalArgumentException("jump height must be 1 to 4");
        }
        if (maxGap < 1 || maxGap > 8) {
            throw new IllegalArgumentException("max gap must be 1 to 8");
        }
        if (maxRisingGap < 0 || maxRisingGap > maxGap) {
            throw new IllegalArgumentException("max rising gap must be 0 to the max gap");
        }
        if (safeFall < 1 || safeFall > 16) {
            throw new IllegalArgumentException("safe fall must be 1 to 16");
        }
    }
}
