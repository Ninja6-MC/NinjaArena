package com.ninja6.ninjaarena.match.event;

import com.ninja6.ninjaarena.match.MatchView;
import org.bukkit.event.HandlerList;

/**
 * A round started: every player in the match is alive again, has been readied (health,
 * food and fire reset) and teleported to their team spawn, and PREP has begun. The place to
 * hand out round kits.
 */
public final class RoundStartEvent extends MatchEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int round;
    private final boolean extra;

    public RoundStartEvent(MatchView match, int round, boolean extra) {
        super(match);
        this.round = round;
        this.extra = extra;
    }

    /** The round number, from 1; extra rounds continue the count. */
    public int round() {
        return round;
    }

    /** Whether this is an extra round played to break a tie. */
    public boolean isExtra() {
        return extra;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
