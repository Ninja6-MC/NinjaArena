package com.ninja6.ninjaarena.match.event;

import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchView;
import org.bukkit.event.HandlerList;

/**
 * The match moved to another phase. For PREP, the teams are already at their spawns and
 * {@link RoundStartEvent} follows; for ROUND_END and MATCH_END, {@link RoundEndEvent} and
 * {@link MatchEndEvent} follow with the result. {@code to().isMaxIntensity()} is true when
 * OVERRUN begins.
 */
public final class MatchPhaseChangeEvent extends MatchEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final MatchPhase from;
    private final MatchPhase to;

    public MatchPhaseChangeEvent(MatchView match, MatchPhase from, MatchPhase to) {
        super(match);
        this.from = from;
        this.to = to;
    }

    public MatchPhase from() {
        return from;
    }

    public MatchPhase to() {
        return to;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
