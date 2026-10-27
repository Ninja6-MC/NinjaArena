package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.PlayerRestorer;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.support.TestServer;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchDeathListenerTest extends MatchFixture {

    private MatchProtectionListener protection;

    @BeforeEach
    void deathHandling() {
        configure(player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        });
    }

    private void configure(Function<Player, RestoreResult> restore) {
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, restore, event -> {
            events.add(event);
            switch (event) {
                case PlayerEliminatedEvent eliminated -> deaths.onEliminated(eliminated);
                case PlayerLeaveMatchEvent left -> deaths.onLeave(left);
                case RoundStartEvent started -> {
                    deaths.onRoundStart(started);
                    protection.onRoundStart(started);
                }
                default -> { }
            }
        }, player -> readied.add(player.getUniqueId()));
        deaths = new MatchDeathListener(plugin, matches, arenas);
        listener = new MatchListener(matches, deaths);
        protection = new MatchProtectionListener(matches, plugin.getServer()::getPlayer, false, deaths::isSpectating);
        gameModeChanges = protection::onGameMode;
        teleportEvents = event -> {
            deaths.onTeleport(event);
            if (!event.isCancelled()) {
                listener.onTeleport(event);
            }
        };
    }

    private PlayerDeathEvent death(UUID id, UUID killer) {
        var fake = players.get(id);
        fake.killer = killer == null ? null : player(killer);
        fake.dead = true;
        var event = deathEvent(id);
        deaths.onDeath(event);
        deaths.onDeathBetweenRounds(event);
        listener.onDeath(event);
        return event;
    }

    private PlayerDeathEvent deathEvent(UUID id) {
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                new Class<?>[] {DamageSource.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        var event = new PlayerDeathEvent(player(id), source,
                new ArrayList<>(List.of(new ItemStack() { })), 8, Component.empty(), true);
        event.getItemsToKeep().add(new ItemStack() { });
        return event;
    }

    private PlayerRespawnEvent respawn(UUID id) {
        var event = new PlayerRespawnEvent(player(id), new Location(overworld, 0.5, 65, 0.5),
                false, false, false, PlayerRespawnEvent.RespawnReason.DEATH);
        listener.onRespawn(event);
        players.get(id).dead = false;
        players.get(id).location = event.getRespawnLocation().clone();
        deaths.onPostRespawn(new PlayerPostRespawnEvent(player(id), event.getRespawnLocation(),
                false, false, false, PlayerRespawnEvent.RespawnReason.DEATH));
        return event;
    }

    @Test
    void arenaDeathsRetainInventoryAndLevelsWithoutDroppingItemsOrExperience() {
        startTwoOnTwo();
        players.get(ALICE).storage = new ItemStack[] {new ItemStack() { }};
        var event = death(ALICE, BOB);
        assertTrue(event.getDrops().isEmpty());
        assertTrue(event.getItemsToKeep().isEmpty());
        assertEquals(0, event.getDroppedExp());
        assertFalse(event.shouldDropExperience());
        assertTrue(event.getKeepInventory());
        assertTrue(event.getKeepLevel());
        assertEquals(1, player(ALICE).getInventory().getStorageContents().length);
        assertFalse(matches.matchOf(ALICE).orElseThrow().isAlive(ALICE));
        assertTrue(deaths.isSpectating(ALICE));
    }

    @Test
    void deathsAndRespawnsOutsideAMatchAreUnaffected() {
        var event = death(ALICE, null);
        assertEquals(1, event.getDrops().size());
        assertEquals(8, event.getDroppedExp());
        assertFalse(event.getKeepInventory());
        assertFalse(event.getKeepLevel());
        assertFalse(deaths.isSpectating(ALICE));
        var respawn = respawn(ALICE);
        assertEquals("world", respawn.getRespawnLocation().getWorld().getName());
        assertEquals(GameMode.SURVIVAL, player(ALICE).getGameMode());
    }

    @Test
    void eliminatedPlayersSpectateTheirOwnLivingTeamAfterRespawn() {
        startTwoOnTwo();
        death(ALICE, BOB);
        assertEquals(GameMode.ADVENTURE, player(ALICE).getGameMode());
        var event = respawn(ALICE);
        assertEquals(player(CAROL).getLocation(), event.getRespawnLocation());
        assertEquals(GameMode.SPECTATOR, player(ALICE).getGameMode());
        assertSame(player(CAROL), player(ALICE).getSpectatorTarget());
        assertTrue(matches.isDisarmed(ALICE));
    }

    @Test
    void theModeGuardAllowsSpectatorsOnlyWhenEliminatedAndRejectsOtherModes() {
        startTwoOnTwo();
        for (GameMode mode : List.of(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.SPECTATOR)) {
            assertTrue(modeChange(ALICE, mode).isCancelled());
        }
        death(ALICE, BOB);
        assertFalse(modeChange(ALICE, GameMode.SPECTATOR).isCancelled());
        for (GameMode mode : List.of(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE)) {
            assertTrue(modeChange(ALICE, mode).isCancelled());
        }
        death(BOB, ALICE);
        death(DAVE, ALICE);
        runNextTick();
        // The engine clears alivePlayers at round end; a surviving winner still cannot spectate.
        assertFalse(matches.matchOf(CAROL).orElseThrow().isAlive(CAROL));
        assertTrue(modeChange(CAROL, GameMode.SPECTATOR).isCancelled());
    }

    private PlayerGameModeChangeEvent modeChange(UUID id, GameMode mode) {
        var event = new PlayerGameModeChangeEvent(player(id), mode, PlayerGameModeChangeEvent.Cause.PLUGIN, null);
        protection.onGameMode(event);
        return event;
    }

    @Test
    void camerasRejectEnemiesAndMobsButAllowAnotherLivingTeammate() {
        startTwoOnTwo();
        death(ALICE, BOB);
        respawn(ALICE);
        var ownTeam = new PlayerStartSpectatingEntityEvent(player(ALICE), player(CAROL), player(CAROL));
        deaths.onStartSpectating(ownTeam);
        assertFalse(ownTeam.isCancelled());
        var enemy = new PlayerStartSpectatingEntityEvent(player(ALICE), player(CAROL), player(BOB));
        deaths.onStartSpectating(enemy);
        assertTrue(enemy.isCancelled());
        Entity mob = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[] {Entity.class},
                (p, m, a) -> { throw new UnsupportedOperationException(m.getName()); });
        var other = new PlayerStartSpectatingEntityEvent(player(ALICE), player(CAROL), mob);
        deaths.onStartSpectating(other);
        assertTrue(other.isCancelled());
    }

    @Test
    void spectatorMovementIsLimitedToTheArenaAndSixteenBlocksFromTheTeam() {
        startTwoOnTwo();
        death(ALICE, BOB);
        respawn(ALICE);
        Location base = player(CAROL).getLocation();
        var nearby = new PlayerMoveEvent(player(ALICE), base, base.clone().add(5, 0, 0));
        deaths.onMove(nearby);
        assertEquals(base.clone().add(5, 0, 0), nearby.getTo());
        for (Location to : List.of(base.clone().add(17, 0, 0), new Location(arenaWorld, 0, 64, 0),
                new Location(overworld, base.getX(), base.getY(), base.getZ()))) {
            var move = new PlayerMoveEvent(player(ALICE), base, to);
            deaths.onMove(move);
            assertEquals(base, move.getTo());
            var teleport = new PlayerTeleportEvent(player(ALICE), base, to, PlayerTeleportEvent.TeleportCause.SPECTATE);
            deaths.onTeleport(teleport);
            assertTrue(teleport.isCancelled());
        }
        // An ordinary active participant can still leave via the existing teleport flow.
        var living = new PlayerTeleportEvent(player(CAROL), base, new Location(overworld, 0, 64, 0));
        deaths.onTeleport(living);
        assertFalse(living.isCancelled());
    }

    @Test
    void whenNoLivingTeammateRemainsSpectatorsWaitNearTheirOwnBase() {
        startTwoOnTwo();
        death(ALICE, BOB);
        respawn(ALICE);
        death(CAROL, BOB);
        deaths.tick();
        assertNull(player(ALICE).getSpectatorTarget());
        assertAt(SPAWN_1, player(ALICE).getLocation());
        assertAt(SPAWN_1, respawn(CAROL).getRespawnLocation());
        assertEquals(GameMode.SPECTATOR, player(CAROL).getGameMode());
        var move = new PlayerMoveEvent(player(ALICE), player(ALICE).getLocation(),
                new Location(arenaWorld, SPAWN_2.x(), SPAWN_2.y(), SPAWN_2.z()));
        deaths.onMove(move);
        assertAt(SPAWN_1, move.getTo());
    }

    @Test
    void retargetingAndThePeriodicTetherHandleTeammateDeparturesAndOutOfBoundsCameras() {
        startTwoOnTwo();
        death(ALICE, BOB);
        respawn(ALICE);
        players.get(CAROL).location = new Location(arenaWorld, 1000, 70, 1000);
        players.get(ALICE).location = players.get(CAROL).location.clone();
        deaths.tick();
        assertNull(player(ALICE).getSpectatorTarget());
        assertAt(SPAWN_1, player(ALICE).getLocation());
        players.get(CAROL).location = new Location(arenaWorld, 40, 64, 40);
        deaths.tick();
        assertSame(player(CAROL), player(ALICE).getSpectatorTarget());
        matches.leave(player(CAROL), MatchService.Exit.QUIT);
        deaths.tick();
        assertNull(player(ALICE).getSpectatorTarget());
        assertAt(SPAWN_1, player(ALICE).getLocation());
    }

    @Test
    void theNextRoundReturnsSpectatorsToAdventureAtTheirOwnBase() {
        startTwoOnTwo();
        death(ALICE, BOB);
        respawn(ALICE);
        death(BOB, CAROL);
        death(DAVE, CAROL);
        runNextTick();
        ticks(1);
        assertEquals(GameMode.ADVENTURE, player(ALICE).getGameMode());
        assertNull(player(ALICE).getSpectatorTarget());
        assertAt(SPAWN_1, player(ALICE).getLocation());
        assertFalse(deaths.isSpectating(ALICE));
        assertFalse(matches.isDisarmed(ALICE));
    }

    @Test
    void aPlayerStillOnTheDeathScreenIsPlacedAndReadiedOnlyWhenTheyRespawn() {
        startTwoOnTwo();
        death(ALICE, BOB);
        death(BOB, CAROL);
        death(DAVE, CAROL);
        runNextTick();
        int teleports = players.get(ALICE).teleports.size();
        long readies = readied.stream().filter(ALICE::equals).count();
        ticks(1);
        assertEquals(teleports, players.get(ALICE).teleports.size());
        assertEquals(readies, readied.stream().filter(ALICE::equals).count());
        assertTrue(players.get(ALICE).dead);
        assertAt(SPAWN_1, respawn(ALICE).getRespawnLocation());
        assertEquals(GameMode.ADVENTURE, player(ALICE).getGameMode());
        assertNull(player(ALICE).getSpectatorTarget());
    }

    @Test
    void leavingWhileSpectatingUsesTheExistingRestoreAndRestoresEverySavedGameMode() throws Exception {
        TestServer.install();
        Logger logger = Logger.getAnonymousLogger();
        var snapshots = new SnapshotService(new SnapshotStore(dir.resolve("snapshots")), logger);
        byte[] emptyItems = {1, 0, 0, 0, 0};
        var restores = new RestoreService(snapshots, new PlayerRestorer(logger), logger);
        for (GameMode mode : GameMode.values()) {
            snapshots.persist(new PlayerSnapshot(ALICE, "Alice", Instant.now(),
                    new SnapshotLocation(new UUID(0, 99), "world", 0.5, 64, 0.5, 0, 0),
                    emptyItems, emptyItems, emptyItems, 0, 0, 0, 20, 20, 5, 0, List.of(), mode, 0, 0));
            configure(restores::restore);
            startTwoOnTwo();
            var event = death(ALICE, BOB);
            respawn(ALICE);
            assertEquals(GameMode.SPECTATOR, player(ALICE).getGameMode());
            assertSame(player(CAROL), player(ALICE).getSpectatorTarget());
            players.get(ALICE).storage = new ItemStack[] {new ItemStack() { }};
            assertTrue(matches.leave(player(ALICE), MatchService.Exit.COMMAND));
            assertFalse(matches.isInMatch(ALICE));
            assertEquals(mode, player(ALICE).getGameMode());
            assertNull(player(ALICE).getSpectatorTarget());
            assertEquals("world", player(ALICE).getLocation().getWorld().getName());
            assertEquals(0, player(ALICE).getInventory().getStorageContents().length);
            assertTrue(event.getDrops().isEmpty());
            assertFalse(snapshots.hasPending(ALICE));
            assertFalse(deaths.isSpectating(ALICE));
            deaths.tick();
            assertEquals(mode, player(ALICE).getGameMode());
            matches.close();
        }
    }

    @Test
    void quittingWhileSpectatingLeavesRestorationToRejoinAndStopsTheTether() {
        startTwoOnTwo();
        death(ALICE, BOB);
        respawn(ALICE);
        assertTrue(matches.leave(player(ALICE), MatchService.Exit.QUIT));
        assertTrue(restored.isEmpty());
        assertFalse(deaths.isSpectating(ALICE));
        assertNull(player(ALICE).getSpectatorTarget());
        int teleports = players.get(ALICE).teleports.size();
        deaths.tick();
        assertEquals(teleports, players.get(ALICE).teleports.size());
    }

    @Test
    void deathsBetweenRoundsSpectateUntilTheNextRoundWithoutASecondElimination() {
        startTwoOnTwo();
        death(BOB, CAROL);
        death(DAVE, CAROL);
        runNextTick();
        assertEquals(MatchPhase.ROUND_END, matches.matchOf(ALICE).orElseThrow().phase());
        int eliminations = events(PlayerEliminatedEvent.class).size();
        death(ALICE, null);
        respawn(ALICE);
        assertEquals(eliminations, events(PlayerEliminatedEvent.class).size());
        assertEquals(GameMode.SPECTATOR, player(ALICE).getGameMode());
        assertSame(player(CAROL), player(ALICE).getSpectatorTarget());
        ticks(1);
        assertEquals(GameMode.ADVENTURE, player(ALICE).getGameMode());
        assertAt(SPAWN_1, player(ALICE).getLocation());
    }

    @Test
    void leavingDeadDefersTheRestoreAndPostRespawnDoesNotPutThePlayerBackInTheMatch() throws Exception {
        TestServer.install();
        Logger logger = Logger.getAnonymousLogger();
        var snapshots = new SnapshotService(new SnapshotStore(dir.resolve("snapshots")), logger);
        byte[] emptyItems = {1, 0, 0, 0, 0};
        snapshots.persist(new PlayerSnapshot(ALICE, "Alice", Instant.now(),
                new SnapshotLocation(new UUID(0, 99), "world", 0.5, 64, 0.5, 0, 0),
                emptyItems, emptyItems, emptyItems, 0, 0, 0, 20, 20, 5, 0, List.of(), GameMode.CREATIVE, 0, 0));
        var restores = new RestoreService(snapshots, new PlayerRestorer(logger), logger);
        configure(restores::restore);
        startTwoOnTwo();
        death(ALICE, BOB);
        assertTrue(matches.leave(player(ALICE), MatchService.Exit.COMMAND));
        assertTrue(snapshots.hasPending(ALICE));
        assertTrue(restores.shouldRestoreOnRespawn(ALICE));
        assertFalse(deaths.isSpectating(ALICE));
        respawn(ALICE);
        assertFalse(matches.isInMatch(ALICE));
        assertEquals(RestoreResult.RESTORED, restores.restore(player(ALICE)));
        assertEquals(GameMode.CREATIVE, player(ALICE).getGameMode());
        deaths.tick();
        assertEquals(GameMode.CREATIVE, player(ALICE).getGameMode());
        assertFalse(snapshots.hasPending(ALICE));
    }

    @Test
    void cancelledDeathsDoNotChangeDropsOrEliminateThePlayer() {
        startTwoOnTwo();
        var event = deathEvent(ALICE);
        event.setCancelled(true);
        deaths.onDeath(event);
        deaths.onDeathBetweenRounds(event);
        listener.onDeath(event);
        assertEquals(1, event.getDrops().size());
        assertEquals(8, event.getDroppedExp());
        assertFalse(event.getKeepInventory());
        assertTrue(matches.matchOf(ALICE).orElseThrow().isAlive(ALICE));
        assertFalse(deaths.isSpectating(ALICE));
        assertTrue(events(PlayerEliminatedEvent.class).isEmpty());
    }
}
