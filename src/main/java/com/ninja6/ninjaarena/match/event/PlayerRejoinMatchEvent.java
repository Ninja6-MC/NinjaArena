package com.ninja6.ninjaarena.match.event;

import com.ninja6.ninjaarena.match.MatchView;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * A player who disconnected from a match rejoined the server within the grace period and is
 * back in it. Called once they have been placed: at their team spawn and alive during PREP,
 * in the shop area during SHOP, otherwise where they joined, out of the round until the next
 * one. Death handling makes them spectate their team, and the round kit is given to a player
 * who is alive.
 */
public final class PlayerRejoinMatchEvent extends MatchEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final int team;

    public PlayerRejoinMatchEvent(MatchView match, UUID player, int team) {
        super(match);
        this.player = player;
        this.team = team;
    }

    public UUID player() {
        return player;
    }

    /** The team the player is on, numbered from 1. */
    public int team() {
        return team;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
