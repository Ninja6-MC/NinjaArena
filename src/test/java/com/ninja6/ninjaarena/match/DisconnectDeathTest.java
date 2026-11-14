package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.match.MatchService.Exit;
import com.ninja6.ninjaarena.match.MatchUpdate.Cause;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.stats.MatchRecord;
import com.ninja6.ninjaarena.stats.PlayerStats;
import com.ninja6.ninjaarena.stats.StatsTracker;
import com.google.common.base.Function;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.damage.DamageSource;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.junit.jupiter.api.Test;

/**
 * A disconnect during the fight counts as a death, end to end with the real match engine,
 * team scores, personal points and stats: the kill goes to the last enemy who hurt the
 * player within the window, or their team pays the penalty, and nothing happens outside the
 * fight. Team 1 is Alice and Carol, team 2 Bob and Dave.
 */
class DisconnectDeathTest extends MatchFixture {

    private static final int GRACE = 30;

    private final List<MatchRecord> records = new ArrayList<>();
    private TeamScores scores;
    private PlayerPoints points;

    /** Prep 2 and combat 20, so the round second is the tick count while it is fought. */
    private static MatchSettings settings(int window, int penalty) {
        return new MatchSettings(2, 20, 2, 1, 2, 2, 1, window, penalty);
    }

    private void start(MatchSettings settings) {
        start(settings, ShopSettings.OFF);
    }

    private void start(MatchSettings settings, ShopSettings shop) {
        scores = new TeamScores();
        points = new PlayerPoints(PointsSettings.DEFAULTS);
        TeamScoringListener scoring = new TeamScoringListener(scores, ScoringSettings.DEFAULTS,
                plugin.getServer()::getPlayer);
        StatsTracker tracker = new StatsTracker(records::add, plugin.getServer()::getPlayer, () -> Instant.EPOCH);
        matches = new MatchService(plugin, arenas, () -> arenaWorld, settings, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, event -> {
            events.add(event);
            switch (event) {
                case MatchPhaseChangeEvent phase -> tracker.onPhase(phase);
                case PlayerEliminatedEvent out -> {
                    scoring.onEliminated(out);
                    points.onEliminated(out);
                    tracker.onEliminated(out);
                }
                case RoundEndEvent end -> {
                    scoring.onRoundEnd(end);
                    points.onRoundEnd(end);
                    tracker.onRoundEnd(end);
                }
                case PlayerLeaveMatchEvent leave -> points.onLeave(leave);
                case MatchEndEvent end -> {
                    scoring.onMatchEnd(end);
                    points.onMatchEnd(end);
                    tracker.onMatchEnd(end);
                }
                default -> { }
            }
        }, player -> readied.add(player.getUniqueId()));
        matches.setStandings(scores);
        matches.setRejoinGraceSeconds(GRACE);
        matches.setShopSettings(shop);
        startTwoOnTwo();
    }

    private void toCombat(MatchSettings settings) {
        start(settings);
        ticks(2);
        assertEquals(MatchPhase.COMBAT, match().phase());
    }

    private MatchView match() {
        return matches.match("dojo").orElseThrow();
    }

    private void quit(UUID id) {
        matches.disconnect(player(id));
        players.get(id).online = false;
    }

    private void join(UUID id) {
        players.get(id).online = true;
        assertTrue(matches.rejoin(player(id)));
        runNextTick();
    }

    private void hit(UUID victim, UUID attacker) {
        matches.damaged(victim, attacker);
    }

    private Optional<PlayerEliminatedEvent> eliminated(UUID id) {
        return events(PlayerEliminatedEvent.class).stream().filter(out -> out.player().equals(id)).findFirst();
    }

    /** Stops the match so the stats tracker hands over its record. */
    private PlayerStats stats(UUID id) {
        matches.close();
        return records.getLast().players().stream().filter(row -> row.player().equals(id)).findFirst().orElseThrow();
    }

    // ---- The kill ----

    @Test
    void byDefaultTheLastEnemyToHurtThemAnyTimeInTheRoundGetsTheKill() {
        // The disconnect settings left at their defaults.
        toCombat(new MatchSettings(2, 20, 2, 1, 2, 2, 1));
        hit(CAROL, DAVE);
        hit(CAROL, BOB);
        ticks(15);
        quit(CAROL);

        PlayerEliminatedEvent out = eliminated(CAROL).orElseThrow();
        assertEquals(Cause.DISCONNECTED, out.cause());
        assertTrue(out.isDeath());
        assertEquals(Optional.of(BOB), out.killer());
        assertFalse(match().isAlive(CAROL));
        assertTrue(matches.isHeld(CAROL), "the hold is unchanged");
        assertEquals(2, scores.score(match(), 2), "the kill's team points");
        assertEquals(0, scores.score(match(), 1), "no penalty when someone is credited");
        assertEquals(2, points.points(match(), BOB), "the kill's personal points");
        assertTrue(players.get(ALICE).messages.contains("Carol disconnected mid-fight; Bob gets the kill."));
        assertTrue(players.get(ALICE).messages.contains("Carol disconnected; they have 30 seconds to rejoin."));

        PlayerStats bob = stats(BOB);
        assertEquals(1, bob.playerKills());
        assertEquals(1, stats(CAROL).deaths());
    }

    @Test
    void theMatchListenerFeedsHitsThatWereNotCancelled() {
        toCombat(settings(0, 2));
        MatchListener feed = new MatchListener(matches, new MatchDeathListener(plugin, matches, arenas));
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                new Class<?>[] {DamageSource.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        feed.onHit(new EntityDamageByEntityEvent(player(BOB), player(CAROL), DamageCause.ENTITY_ATTACK, source, 2));
        quit(CAROL);
        assertEquals(Optional.of(BOB), eliminated(CAROL).orElseThrow().killer());
    }

    @Test
    void aHitAShieldFullyBlocksEarnsNoCredit() {
        toCombat(settings(0, 2));
        MatchListener feed = new MatchListener(matches, new MatchDeathListener(plugin, matches, arenas));
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                new Class<?>[] {DamageSource.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        Map<DamageModifier, Double> modifiers = new EnumMap<>(DamageModifier.class);
        modifiers.put(DamageModifier.BASE, 4.0);
        modifiers.put(DamageModifier.BLOCKING, -4.0);
        Map<DamageModifier, Function<? super Double, Double>> functions = new EnumMap<>(DamageModifier.class);
        functions.put(DamageModifier.BASE, damage -> damage);
        functions.put(DamageModifier.BLOCKING, damage -> -damage);
        EntityDamageByEntityEvent blocked = new EntityDamageByEntityEvent(player(BOB), player(CAROL),
                DamageCause.ENTITY_ATTACK, source, modifiers, functions);
        assertEquals(0, blocked.getFinalDamage());
        feed.onHit(blocked);
        quit(CAROL);
        assertTrue(eliminated(CAROL).orElseThrow().killer().isEmpty(), "Bob did no damage");
    }

    @Test
    void withAWindowTheLastEnemyInsideItGetsTheKill() {
        toCombat(settings(3, 2));
        hit(CAROL, DAVE);
        ticks(4);
        hit(CAROL, BOB);
        ticks(3);
        // Bob hit 3 seconds ago, on the edge of the window; Dave's hit is older.
        quit(CAROL);
        assertEquals(Optional.of(BOB), eliminated(CAROL).orElseThrow().killer());
        assertEquals(2, scores.score(match(), 2));
    }

    @Test
    void aHitOutsideTheWindowIsNotCreditedAndTheTeamPaysThePenalty() {
        toCombat(settings(3, 2));
        scores.addPoints(match(), 1, 5);
        hit(CAROL, BOB);
        ticks(4);
        quit(CAROL);

        PlayerEliminatedEvent out = eliminated(CAROL).orElseThrow();
        assertTrue(out.isDeath());
        assertTrue(out.killer().isEmpty());
        assertEquals(3, scores.score(match(), 1), "5 less the penalty of 2");
        assertEquals(0, scores.score(match(), 2));
        assertEquals(0, points.points(match(), BOB));
        assertTrue(players.get(ALICE).messages.contains("Carol disconnected mid-fight; team 1 loses 2 points."));
        assertEquals(0, stats(BOB).playerKills());
        assertEquals(1, stats(CAROL).deaths(), "still a death");
    }

    @Test
    void thePenaltyNeverTakesATeamBelowZero() {
        toCombat(settings(0, 2));
        scores.addPoints(match(), 1, 1);
        quit(CAROL);
        assertEquals(0, scores.score(match(), 1));
    }

    @Test
    void aPenaltyOfZeroTurnsItOff() {
        toCombat(settings(0, 0));
        scores.addPoints(match(), 1, 5);
        quit(CAROL);

        PlayerEliminatedEvent out = eliminated(CAROL).orElseThrow();
        assertTrue(out.isDeath());
        assertTrue(out.killer().isEmpty());
        assertEquals(5, scores.score(match(), 1));
        assertTrue(players.get(ALICE).messages.stream().noneMatch(line -> line.contains("mid-fight")));
    }

    @Test
    void aTeammatesHitIsNeverCredited() {
        toCombat(settings(0, 2));
        scores.addPoints(match(), 1, 5);
        hit(CAROL, ALICE);
        hit(CAROL, CAROL);
        quit(CAROL);
        assertTrue(eliminated(CAROL).orElseThrow().killer().isEmpty());
        assertEquals(3, scores.score(match(), 1), "the penalty, as with no hit at all");
    }

    @Test
    void hitsFromAnEarlierRoundDoNotCount() {
        toCombat(settings(0, 2));
        hit(CAROL, BOB);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(ALICE));
        runNextTick();
        assertEquals(MatchPhase.ROUND_END, match().phase());
        ticks(1 + 2);
        assertEquals(MatchPhase.COMBAT, match().phase());
        assertEquals(2, match().round());
        quit(CAROL);
        assertTrue(eliminated(CAROL).orElseThrow().killer().isEmpty());
    }

    @Test
    void withNoGraceAQuitInTheFightIsADeathToo() {
        toCombat(settings(0, 2));
        matches.setRejoinGraceSeconds(0);
        hit(CAROL, BOB);
        quit(CAROL);

        assertFalse(matches.isInMatch(CAROL));
        PlayerEliminatedEvent out = eliminated(CAROL).orElseThrow();
        assertEquals(Cause.LEFT, out.cause());
        assertTrue(out.isDeath());
        assertEquals(Optional.of(BOB), out.killer());
        assertEquals(2, scores.score(match(), 2));
        assertEquals(1, stats(CAROL).deaths(), "a leaver keeps their death");
    }

    @Test
    void theLeaveCommandIsNoDeath() {
        toCombat(settings(0, 2));
        scores.addPoints(match(), 1, 5);
        hit(CAROL, BOB);
        matches.leave(player(CAROL), Exit.COMMAND);
        PlayerEliminatedEvent out = eliminated(CAROL).orElseThrow();
        assertFalse(out.isDeath());
        assertTrue(out.killer().isEmpty());
        assertEquals(5, scores.score(match(), 1));
        assertEquals(0, scores.score(match(), 2));
    }

    // ---- Outside the fight ----

    @Test
    void aDisconnectInPrepIsNoDeathAndNoPenalty() {
        start(settings(0, 2));
        assertEquals(MatchPhase.PREP, match().phase());
        scores.addPoints(match(), 1, 5);
        quit(CAROL);

        PlayerEliminatedEvent out = eliminated(CAROL).orElseThrow();
        assertEquals(Cause.DISCONNECTED, out.cause());
        assertFalse(out.isDeath());
        assertEquals(5, scores.score(match(), 1));
        assertTrue(players.get(ALICE).messages.stream().noneMatch(line -> line.contains("mid-fight")));
        assertEquals(0, stats(CAROL).deaths());
    }

    @Test
    void aDisconnectInTheShopIsNoDeathAndNoPenalty() {
        start(settings(0, 2), new ShopSettings(true, 10, 2, 3));
        ticks(2);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(ALICE));
        runNextTick();
        ticks(1);
        assertEquals(MatchPhase.SHOP, match().phase());
        quit(CAROL);
        assertTrue(eliminated(CAROL).isEmpty(), "nobody is alive in a round during the shop");
        assertEquals(ScoringSettings.DEFAULTS.roundWinPoints() + 2 * ScoringSettings.DEFAULTS.playerKillPoints(),
                scores.score(match(), 1), "the round win and Alice's two kills, no penalty");
        assertEquals(0, stats(CAROL).deaths());
    }

    // ---- Rejoining ----

    @Test
    void aRejoinAfterACreditedDisconnectSpectatesAndIsNeverCountedTwice() {
        toCombat(settings(0, 2));
        hit(CAROL, BOB);
        quit(CAROL);
        join(CAROL);

        assertFalse(match().isAlive(CAROL), "not revived into the round");
        assertFalse(match().isDisconnected(CAROL));
        // Hurt and gone again in the same round: she is already out of it.
        hit(CAROL, DAVE);
        quit(CAROL);
        join(CAROL);
        assertEquals(1, events(PlayerEliminatedEvent.class).stream()
                .filter(out -> out.player().equals(CAROL)).count());
        assertEquals(2, scores.score(match(), 2), "one kill");
        assertEquals(2, points.points(match(), BOB));
        assertEquals(0, points.points(match(), DAVE));

        // She plays the next round.
        matches.died(player(ALICE), player(BOB));
        runNextTick();
        assertEquals(MatchPhase.ROUND_END, match().phase());
        ticks(1);
        assertEquals(MatchPhase.PREP, match().phase());
        assertTrue(match().isAlive(CAROL));

        assertEquals(1, stats(CAROL).deaths(), "the first disconnect only");
    }

    // ---- A damager who has left ----

    @Test
    void aDamagerWhoHasLeftIsPassedOverForAnEarlierOne() {
        toCombat(settings(0, 2));
        hit(CAROL, DAVE);
        ticks(1);
        hit(CAROL, BOB);
        matches.leave(player(BOB), Exit.COMMAND);
        quit(CAROL);
        assertEquals(Optional.of(DAVE), eliminated(CAROL).orElseThrow().killer());
        assertEquals(2, points.points(match(), DAVE));
    }

    @Test
    void withOnlyADamagerWhoHasLeftTheTeamPaysThePenalty() {
        toCombat(settings(0, 2));
        scores.addPoints(match(), 1, 5);
        hit(CAROL, BOB);
        matches.leave(player(BOB), Exit.COMMAND);
        quit(CAROL);
        assertTrue(eliminated(CAROL).orElseThrow().killer().isEmpty());
        assertEquals(3, scores.score(match(), 1));
    }
}
