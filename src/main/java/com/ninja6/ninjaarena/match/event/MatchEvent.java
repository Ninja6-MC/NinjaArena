package com.ninja6.ninjaarena.match.event;

import com.ninja6.ninjaarena.match.MatchView;
import java.util.Objects;
import org.bukkit.event.Event;

/**
 * Base of every match event. All of them are called on the server thread, synchronously,
 * by {@code MatchService}, after the change they report has been applied: {@link #match()}
 * already shows the new state. None can be cancelled.
 */
public abstract class MatchEvent extends Event {

    private final MatchView match;

    protected MatchEvent(MatchView match) {
        this.match = Objects.requireNonNull(match, "match");
    }

    /** The match, as it is now. */
    public MatchView match() {
        return match;
    }
}
