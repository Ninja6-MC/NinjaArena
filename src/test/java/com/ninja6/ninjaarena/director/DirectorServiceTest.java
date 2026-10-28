package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.director.MatchDirectorTest.FakeEnemy;
import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchResult;
import com.ninja6.ninjaarena.match.MatchUpdate.Cause;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DirectorServiceTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    /** A repeating task handed to the fake scheduler. */
    private static final class Loop {
        final Runnable body;
        final long delay;
        final long period;
        boolean cancelled;

        Loop(Runnable body, long delay, long period) {
            this.body = body;
            this.delay = delay;
            this.period = period;
        }
    }

    private final List<Loop> loops = new ArrayList<>();
    private final List<FakeEnemy> spawned = new ArrayList<>();
    private final List<MatchView> spawnersMade = new ArrayList<>();
    private FakeMatch match;
    private DirectorService service;
    private boolean failSpawns;

    @BeforeEach
    void setUp() {
        match = new FakeMatch(List.of(List.of(A), List.of(B)));
        service = new DirectorService(plugin(), IntensitySettings.DEFAULTS, new ThreatBudget(20), EnemyCosts.DEFAULTS,
                (view, team) -> 0, id -> match.contains(id) ? Optional.of(match) : Optional.empty(), view -> {
                    spawnersMade.add(view);
                    return (kind, target) -> {
                        if (failSpawns) {
                            throw new IllegalStateException("spawn failed");
                        }
                        FakeEnemy enemy = new FakeEnemy(kind, target);
                        spawned.add(enemy);
                        return Optional.of(enemy);
                    };
                }, () -> 0);
    }

    private Plugin plugin() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(BukkitScheduler.class.getClassLoader(),
                new Class<?>[] {BukkitScheduler.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("runTaskTimer") || !(args[1] instanceof Runnable body)) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    Loop loop = new Loop(body, (Long) args[2], (Long) args[3]);
                    loops.add(loop);
                    return (BukkitTask) Proxy.newProxyInstance(BukkitTask.class.getClassLoader(),
                            new Class<?>[] {BukkitTask.class}, (task, call, callArgs) -> switch (call.getName()) {
                                case "cancel" -> {
                                    loop.cancelled = true;
                                    yield null;
                                }
                                case "isCancelled" -> loop.cancelled;
                                default -> throw new UnsupportedOperationException(call.getName());
                            });
                });
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getScheduler" -> scheduler;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "getLogger" -> logger;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private void phase(MatchPhase to) {
        MatchPhase from = match.phase;
        match.phase = to;
        service.onPhaseChange(new MatchPhaseChangeEvent(match, from, to));
    }

    private List<Loop> running() {
        return loops.stream().filter(loop -> !loop.cancelled).toList();
    }

    private void second() {
        running().forEach(loop -> loop.body.run());
    }

    private static MatchResult stopped() {
        return new MatchResult("dojo", MatchResult.Reason.STOPPED, List.of(), List.of(), List.of());
    }

    @Test
    void theLoopRunsOnceASecondFromCombatThroughOverrunAndStopsAtRoundEnd() {
        assertFalse(service.isLooping(match));
        phase(MatchPhase.PREP);
        assertEquals(List.of(), loops);

        match.combat(0);
        phase(MatchPhase.COMBAT);
        assertEquals(1, loops.size());
        assertEquals(DirectorService.TICKS_PER_SECOND, loops.getFirst().delay);
        assertEquals(DirectorService.TICKS_PER_SECOND, loops.getFirst().period);
        assertTrue(service.isLooping(match));
        second();
        assertFalse(spawned.isEmpty());

        phase(MatchPhase.OVERRUN);
        assertEquals(1, loops.size(), "OVERRUN keeps the same loop");
        second();
        assertEquals(1, service.director(match).orElseThrow().intensity());

        phase(MatchPhase.ROUND_END);
        assertTrue(loops.getFirst().cancelled);
        assertFalse(service.isLooping(match));
        assertTrue(service.director(match).isPresent(), "the director outlives the round");

        match.nextRound();
        match.phase = MatchPhase.PREP;
        phase(MatchPhase.COMBAT);
        assertEquals(2, loops.size());
        assertEquals(1, running().size());
        assertEquals(1, spawnersMade.size(), "one director and spawner per match");
    }

    @Test
    void theLoopStopsItselfOnceTheFightIsOver() {
        match.combat(0);
        phase(MatchPhase.COMBAT);
        match.phase = MatchPhase.ROUND_END;
        int before = spawned.size();
        second();
        assertTrue(loops.getFirst().cancelled);
        assertEquals(before, spawned.size());
    }

    @Test
    void matchEndCancelsTheLoopAndDropsTheDirector() {
        match.combat(0);
        phase(MatchPhase.COMBAT);
        match.phase = MatchPhase.MATCH_END;
        service.onMatchEnd(new MatchEndEvent(match, stopped()));
        assertTrue(loops.getFirst().cancelled);
        assertTrue(service.director(match).isEmpty());
    }

    @Test
    void closeCancelsEveryLoopAndStartsNoMore() {
        match.combat(0);
        phase(MatchPhase.COMBAT);
        FakeMatch other = new FakeMatch(List.of(List.of(new UUID(0, 9)))).combat(0);
        service.onPhaseChange(new MatchPhaseChangeEvent(other, MatchPhase.PREP, MatchPhase.COMBAT));
        assertEquals(2, running().size());

        service.close();
        assertEquals(List.of(), running());
        assertTrue(service.director(match).isEmpty());
        phase(MatchPhase.OVERRUN);
        assertEquals(List.of(), running());
        service.onMatchEnd(new MatchEndEvent(match, stopped()));
    }

    @Test
    void aFailingDirectorStopsItsLoopForTheRound() {
        match.combat(0);
        phase(MatchPhase.COMBAT);
        failSpawns = true;
        second();
        assertTrue(loops.getFirst().cancelled);
        assertFalse(service.isLooping(match));
    }

    @Test
    void eliminationAndLeavingClearTargetsAtOnce() {
        match.combat(0);
        phase(MatchPhase.COMBAT);
        second();
        assertTrue(spawned.stream().allMatch(enemy -> A.equals(enemy.target)));
        service.onEliminated(new PlayerEliminatedEvent(match, A, 1, Cause.DIED, null));
        assertTrue(spawned.stream().allMatch(enemy -> enemy.target == null));

        spawned.forEach(enemy -> enemy.target = B);
        service.onLeave(new PlayerLeaveMatchEvent(match, B, 2));
        assertTrue(spawned.stream().allMatch(enemy -> enemy.target == null));
    }

    @Test
    void damageToAlivePlayersCountsTowardsTheDirector() {
        match.combat(45);
        phase(MatchPhase.COMBAT);
        second();
        MatchDirector director = service.director(match).orElseThrow();
        double calm = director.intensity();
        service.onDamage(damage(player(A), 40));
        service.onDamage(damage(player(new UUID(0, 9)), 400));
        Entity zombie = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[] {Entity.class},
                (proxy, method, args) -> null);
        service.onDamage(damage(zombie, 400));
        second();
        // 40 damage over two players is full stress.
        assertEquals(calm / 2, director.intensity(), 1e-9);
        assertSame(match, director.match());
    }

    @Test
    void damageWithNoDirectorRunningIsIgnored() {
        service.onDamage(damage(player(A), 40));
        assertNull(service.director(match).orElse(null));
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static EntityDamageEvent damage(Entity victim, double amount) {
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                new Class<?>[] {DamageSource.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        return new EntityDamageEvent(victim, DamageCause.ENTITY_ATTACK, source, amount);
    }
}
