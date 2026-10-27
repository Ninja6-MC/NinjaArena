package com.ninja6.ninjaarena.match.event;

import com.ninja6.ninjaarena.match.MatchView;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * A player left the match: by the leave command, by being teleported out of the arena
 * world, or by disconnecting. They are no longer in {@link MatchView#players()}. Unless they
 * disconnected, they are sent home after this event; a disconnected player is sent home
 * when they next join.
 */
public final class PlayerLeaveMatchEvent extends MatchEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final int team;

    public PlayerLeaveMatchEvent(MatchView match, UUID player, int team) {
        super(match);
        this.player = player;
        this.team = team;
    }

    public UUID player() {
        return player;
    }

    /** The team the player was on, numbered from 1. */
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
