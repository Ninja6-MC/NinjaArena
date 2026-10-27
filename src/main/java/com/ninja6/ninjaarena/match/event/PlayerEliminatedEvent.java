package com.ninja6.ninjaarena.match.event;

import com.ninja6.ninjaarena.match.MatchUpdate.Cause;
import com.ninja6.ninjaarena.match.MatchView;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * A player alive in the current round was taken out of it, by dying or by leaving the
 * match. If that decides the round, {@link RoundEndEvent} follows on the next server tick,
 * once everyone who went down in the same tick is counted; a player who left also gets a
 * {@link PlayerLeaveMatchEvent} after this one. The place to award kill points.
 */
public final class PlayerEliminatedEvent extends MatchEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final int team;
    private final Cause cause;
    private final UUID killer;

    public PlayerEliminatedEvent(MatchView match, UUID player, int team, Cause cause, UUID killer) {
        super(match);
        this.player = player;
        this.team = team;
        this.cause = cause;
        this.killer = killer;
    }

    public UUID player() {
        return player;
    }

    /** The team the player was on, numbered from 1. */
    public int team() {
        return team;
    }

    public Cause cause() {
        return cause;
    }

    /** The other player in the same match who killed them, if one did. */
    public Optional<UUID> killer() {
        return Optional.ofNullable(killer);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
