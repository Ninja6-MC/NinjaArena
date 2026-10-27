package com.ninja6.ninjaarena.match.event;

import com.ninja6.ninjaarena.match.MatchResult;
import com.ninja6.ninjaarena.match.MatchView;
import org.bukkit.event.HandlerList;

/**
 * The match ended; the winners, final standings and every round result are in
 * {@link #result()}. The match is in MATCH_END and its players are sent home when that runs
 * out, so this is the place to show results. Also called, with reason STOPPED, when the
 * plugin is disabled mid-match, and with reason ABANDONED when the last player leaves; in
 * both cases the players are sent home straight after.
 */
public final class MatchEndEvent extends MatchEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final MatchResult result;

    public MatchEndEvent(MatchView match, MatchResult result) {
        super(match);
        this.result = result;
    }

    public MatchResult result() {
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
