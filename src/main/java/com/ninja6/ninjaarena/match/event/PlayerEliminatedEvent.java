package com.ninja6.ninjaarena.match.event;

import com.ninja6.ninjaarena.match.MatchUpdate.Cause;
import com.ninja6.ninjaarena.match.MatchView;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * A player alive in the current round was taken out of it, by dying, disconnecting or
 * leaving the match. If that decides the round, {@link RoundEndEvent} follows on the next server tick,
 * once everyone who went down in the same tick is counted; a player who left also gets a
 * {@link PlayerLeaveMatchEvent} after this one. The place to award kill points.
 */
public final class PlayerEliminatedEvent extends MatchEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final int team;
    private final Cause cause;
    private final UUID killer;
    private final boolean death;

    /** A death only if they died. */
    public PlayerEliminatedEvent(MatchView match, UUID player, int team, Cause cause, UUID killer) {
        this(match, player, team, cause, killer, cause == Cause.DIED);
    }

    public PlayerEliminatedEvent(MatchView match, UUID player, int team, Cause cause, UUID killer, boolean death) {
        super(match);
        this.player = player;
        this.team = team;
        this.cause = cause;
        this.killer = killer;
        this.death = death;
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

    /**
     * The other player in the same match who killed them, if one did. For a disconnect that
     * counts as a death, the last enemy player who damaged them within
     * {@link com.ninja6.ninjaarena.match.MatchSettings#disconnectKillWindowSeconds()}.
     */
    public Optional<UUID> killer() {
        return Optional.ofNullable(killer);
    }

    /**
     * Whether this counts as a death, with a kill for {@link #killer()}: when they died, and
     * when they disconnected during COMBAT or OVERRUN. Score and stats count only these.
     */
    public boolean isDeath() {
        return death;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
