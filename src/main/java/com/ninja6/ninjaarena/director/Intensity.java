package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.match.MatchPhase;
import java.util.Objects;

/**
 * The director's target intensity, from 0 (no enemies) to 1 (the whole round budget in
 * play). Pure arithmetic over the inputs of one second.
 *
 * <p>Combat repeats a cycle: a build-up from the relax level to the round's peak, the peak
 * held, then a short relax. The peak rises every round and the relax level follows it.
 * Combat time adds a ramp towards the end of the round timer; teams knocked out and recent
 * damage taken by the players lower the result. OVERRUN is always 1, and every phase that
 * is not a fight is 0.
 */
public final class Intensity {

    /** Where combat is in its cycle. */
    public enum Stage {
        BUILD_UP,
        PEAK,
        RELAX
    }

    /**
     * What the intensity is worked out from.
     *
     * @param phase              the match phase
     * @param round              the round, from 1
     * @param combatSeconds      seconds since COMBAT started this round
     * @param roundSeconds       length of COMBAT, the round timer
     * @param aliveTeams         teams with a player alive in the round
     * @param startingTeams      teams that started the match with players
     * @param damagePerPlayer    damage taken by alive players in the damage window, divided by
     *                           the players alive
     */
    public record Inputs(MatchPhase phase, int round, int combatSeconds, int roundSeconds, int aliveTeams,
            int startingTeams, double damagePerPlayer) {

        public Inputs {
            Objects.requireNonNull(phase, "phase");
        }
    }

    private Intensity() {
    }

    /** The stage of the cycle at a number of seconds into combat. */
    public static Stage stage(IntensitySettings settings, int combatSeconds) {
        int at = Math.floorMod(Math.max(0, combatSeconds), settings.cycleSeconds());
        if (at < settings.buildUpSeconds()) {
            return Stage.BUILD_UP;
        }
        return at < settings.buildUpSeconds() + settings.peakSeconds() ? Stage.PEAK : Stage.RELAX;
    }

    /**
     * The cycle alone for a round: the relax level at the start of a build-up, rising evenly
     * to the round's peak, held there, then back to the relax level.
     */
    public static double cycle(IntensitySettings settings, int round, int combatSeconds) {
        double peak = settings.peak(round);
        double relax = peak * settings.relaxLevel();
        int at = Math.floorMod(Math.max(0, combatSeconds), settings.cycleSeconds());
        double shape = switch (stage(settings, combatSeconds)) {
            case BUILD_UP -> (double) at / settings.buildUpSeconds();
            case PEAK -> 1;
            case RELAX -> 0;
        };
        return relax + (peak - relax) * shape;
    }

    /** The target intensity for one second, from 0 to 1. */
    public static double target(IntensitySettings settings, Inputs in) {
        if (in.phase().isMaxIntensity()) {
            return 1;
        }
        if (!in.phase().isFighting() || in.aliveTeams() <= 0) {
            return 0;
        }
        double level = cycle(settings, in.round(), in.combatSeconds());
        double progress = Math.clamp((double) Math.max(0, in.combatSeconds()) / Math.max(1, in.roundSeconds()), 0, 1);
        level += settings.timeRamp() * progress;
        double teams = Math.clamp((double) in.aliveTeams() / Math.max(1, in.startingTeams()), 0, 1);
        level *= 1 - settings.teamsWeight() + settings.teamsWeight() * teams;
        double damage = Double.isFinite(in.damagePerPlayer()) ? Math.max(0, in.damagePerPlayer()) : 0;
        double stress = Math.min(1, damage / settings.damageFullStress());
        level *= 1 - settings.damageRelief() * stress;
        return Math.clamp(level, 0, 1);
    }
}
