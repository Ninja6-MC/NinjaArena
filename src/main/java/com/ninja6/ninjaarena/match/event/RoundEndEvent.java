package com.ninja6.ninjaarena.match.event;

import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.RoundResult;
import org.bukkit.event.HandlerList;

/**
 * A round was decided, with a winning team or as a draw, and the match is in ROUND_END. The
 * place to award round-win points: whether another round follows is decided only when
 * ROUND_END runs out, so points awarded here already count towards it.
 */
public final class RoundEndEvent extends MatchEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RoundResult result;

    public RoundEndEvent(MatchView match, RoundResult result) {
        super(match);
        this.result = result;
    }

    public RoundResult result() {
        return result;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
