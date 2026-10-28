package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.director.EnemyCosts;
import com.ninja6.ninjaarena.director.EnemyLifecycle;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.junit.jupiter.api.Test;

class EnemyTeardownTest extends MatchFixture {

    private boolean removed;
    private EnemyLifecycle lifecycle;

    private void useLifecycle() {
        Item item = (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[] {Item.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLocation" -> new Location(arenaWorld, 50, 64, 50);
                    case "remove" -> {
                        assertTrue(arenas.isHeld("dojo"), "cleanup happens while chunks are still held");
                        removed = true;
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        World cleanupWorld = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getEntities")) {
                        return List.of(item);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS,
                player -> RestoreResult.RESTORED, event -> {
                    events.add(event);
                    if (event instanceof MatchEndEvent ended) {
                        lifecycle.onMatchEnd(ended);
                    }
                }, player -> { });
        lifecycle = new EnemyLifecycle(matches::matches, view -> Optional.empty(),
                name -> arenas.get(name).map(Arena::bounds), () -> cleanupWorld,
                new TeamScores(), EnemyCosts.DEFAULTS, () -> 0);
        startTwoOnTwo();
    }

    private void assertClosed() {
        assertTrue(removed);
        assertFalse(arenas.isHeld("dojo"));
        assertTrue(matches.matches().isEmpty());
    }

    @Test
    void completedMatchCleansEntitiesThenReleasesTheArenaAfterReturn() {
        useLifecycle();
        ticks(50);
        assertClosed();
    }

    @Test
    void forfeitCleansEntitiesThenReleasesTickets() {
        useLifecycle();
        ticks(SETTINGS.prepSeconds());
        matches.leave(player(BOB), MatchService.Exit.COMMAND);
        matches.leave(player(DAVE), MatchService.Exit.COMMAND);
        runNextTick();
        ticks(SETTINGS.roundEndSeconds() + SETTINGS.matchEndSeconds());
        assertClosed();
    }

    @Test
    void abandonmentCleansEntitiesAndReleasesTickets() {
        useLifecycle();
        for (var id : List.of(ALICE, BOB, CAROL, DAVE)) {
            matches.leave(player(id), MatchService.Exit.QUIT);
        }
        runNextTick();
        ticks(SETTINGS.matchEndSeconds());
        assertClosed();
    }

    @Test
    void shutdownCleansEntitiesAndReleasesTickets() {
        useLifecycle();
        lifecycle.close();
        matches.close();
        assertClosed();
    }
}
