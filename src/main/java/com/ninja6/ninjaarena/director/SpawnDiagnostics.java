package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.match.MatchView;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;

/**
 * Explains a match's spawns that did not happen: no registered point qualified, or the world
 * cancelled the spawn, or a budget or gate held spawning back. Each reason is logged at most
 * once per round, at INFO with
 * {@link SpawnSettings#debug()} on and at FINE otherwise; the latest one is kept for the
 * director status. Call on the server thread.
 */
public final class SpawnDiagnostics {

    /** What can stop a spawn, before placement, during placement or in the world. */
    private enum Reason {
        NO_POINTS, TOO_CLOSE, IN_VIEW, CHUNK_NOT_LOADED, BLOCKED, CANCELLED, BUDGET, GATE
    }

    private final MatchView match;
    private final Logger logger;
    private final Level level;
    private final Set<Reason> loggedThisRound = EnumSet.noneOf(Reason.class);
    private int loggedRound = -1;
    private String last;

    public SpawnDiagnostics(MatchView match, SpawnSettings settings, Logger logger) {
        this.match = Objects.requireNonNull(match, "match");
        this.logger = Objects.requireNonNull(logger, "logger");
        level = Objects.requireNonNull(settings, "settings").debug() ? Level.INFO : Level.FINE;
    }

    /** No point qualified for an enemy of the kind; {@code refusals} says why, point by point. */
    public void refused(EnemyKind kind, List<SpawnPlacement.Refused> refusals) {
        Set<Reason> reasons = EnumSet.noneOf(Reason.class);
        String detail;
        if (refusals.isEmpty()) {
            reasons.add(Reason.NO_POINTS);
            detail = "the arena has no enemy spawn points";
        } else {
            StringJoiner points = new StringJoiner(", ");
            for (SpawnPlacement.Refused refused : refusals) {
                reasons.add(Reason.valueOf(refused.reason().name()));
                points.add("point " + refused.point() + " " + refused.reason().text());
            }
            detail = points.toString();
        }
        report(reasons, "no enemy spawn point for a " + name(kind) + ": " + detail);
    }

    /** The world cancelled the spawn of an enemy of the kind at the location. */
    public void cancelled(EnemyKind kind, Location at) {
        report(EnumSet.of(Reason.CANCELLED), "the world cancelled a " + name(kind) + " spawn at "
                + String.format(Locale.ROOT, "%.1f, %.1f, %.1f", at.getX(), at.getY(), at.getZ()));
    }

    /** Spawning was held back before placement by the target threat budget or spawn gate. */
    public void blocked(MatchDirector.Blockage reason, double targetThreat, double inPlay) {
        String detail = switch (reason) {
            case BUDGET -> String.format(Locale.ROOT,
                    "the threat budget cannot afford another enemy (target %.2f, in play %.2f)", targetThreat, inPlay);
            case GATE -> "the spawn gate is holding back enemies";
        };
        report(EnumSet.of(Reason.valueOf(reason.name())), detail);
    }

    /** The latest placement, cancellation or limit reason, with its round, logged or not. */
    public Optional<String> last() {
        return Optional.ofNullable(last);
    }

    private void report(Set<Reason> reasons, String what) {
        last = "round " + match.round() + ", " + what;
        if (loggedRound != match.round()) {
            loggedRound = match.round();
            loggedThisRound.clear();
        }
        if (loggedThisRound.containsAll(reasons)) {
            return;
        }
        loggedThisRound.addAll(reasons);
        logger.log(level, "Arena " + match.arena() + ", " + last + ".");
    }

    private static String name(EnemyKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
