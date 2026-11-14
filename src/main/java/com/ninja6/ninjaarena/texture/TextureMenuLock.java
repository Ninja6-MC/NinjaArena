package com.ninja6.ninjaarena.texture;

import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.spectate.Spectators;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Locks texture choices during a match or spectating; lobby membership permits them. */
public final class TextureMenuLock implements Predicate<UUID> {

    private final Supplier<MatchService> matches;
    private final Supplier<Spectators> spectators;

    /** Services are looked up on use because texture menus start before matches. */
    public TextureMenuLock(Supplier<MatchService> matches, Supplier<Spectators> spectators) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.spectators = Objects.requireNonNull(spectators, "spectators");
    }

    @Override
    public boolean test(UUID id) {
        MatchService match = matches.get();
        Spectators viewers = spectators.get();
        return match != null && match.isInMatch(id)
                || viewers != null && viewers.contains(id);
    }
}
