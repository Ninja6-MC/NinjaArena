package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.restore.RestoreListener;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.spectate.SpectatorSettings;
import com.ninja6.ninjaarena.spectate.Spectators;
import com.ninja6.ninjaarena.waiting.WaitingRoomListener;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpectatorLifecycleTest extends MatchFixture {

    private SnapshotStore snapshots;
    private RestoreListener restoreListener;
    private WaitingRoomService waiting;
    private Spectators viewers;

    @BeforeEach
    void setupSpectating() throws Exception {
        snapshots = new SnapshotStore(dir.resolve("snapshots"));
        var restores = new RestoreService(new SnapshotService(snapshots, plugin.getLogger()), (player, snapshot) -> {
            restored.add(player.getUniqueId());
            player.teleport(new Location(overworld, snapshot.location().x(), snapshot.location().y(),
                    snapshot.location().z()));
            player.setGameMode(snapshot.gameMode());
        }, plugin.getLogger());
        restoreListener = new RestoreListener(plugin, restores);
        waiting = new WaitingRoomService(plugin, arenas, WaitingRoomSettings.from(null), restores::restore,
                player -> player.setGameMode(GameMode.ADVENTURE), matches);
        viewers = new Spectators(plugin, arenas, matches, waiting, restores, () -> arenaWorld,
                new SpectatorSettings(true, 1));
        var waitingListener = new WaitingRoomListener(waiting);
        teleportEvents = event -> {
            viewers.onTeleport(event);
            if (!event.isCancelled()) {
                waitingListener.onTeleport(event);
            }
        };
        gameModeChanges = viewers::onGameMode;
    }

    @Test
    void waitingTransferRetainsSnapshotUntilMatchEndAndDoesNotChangeTheRoster() throws Exception {
        snapshots.save(snapshot(CAROL));
        arenas.create("garden", new GridCell(1, 0));
        arenas.update("garden", a -> a.withWaitingRoom(new ArenaPoint(300, 64, 50, 0, 0)));
        players.get(CAROL).location = new Location(arenaWorld, 300, 64, 50);
        arenas.finishEdit("garden");
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        players.get(CAROL).canSpectate = true;
        waiting.join(player(CAROL), "garden");

        viewers.fromWaitingRoom(player(CAROL), "dojo");

        assertFalse(waiting.isWaiting(CAROL));
        assertFalse(matches.isInMatch(CAROL));
        assertEquals(List.of(ALICE, BOB), matches.match("dojo").orElseThrow().players());
        assertEquals(GameMode.SPECTATOR, players.get(CAROL).gameMode);
        assertEquals(snapshot(CAROL), snapshots.load(CAROL).orElseThrow());
        assertTrue(viewers.destination("dojo").isEmpty());

        var move = new PlayerMoveEvent(player(CAROL), player(CAROL).getLocation(),
                new Location(arenaWorld, 20, 101, 20));
        viewers.onMove(move);
        assertTrue(move.isCancelled());
        assertFalse(player(CAROL).teleport(new Location(overworld, 20, 64, 20),
                PlayerTeleportEvent.TeleportCause.PLUGIN));
        player(CAROL).setGameMode(GameMode.CREATIVE);
        assertEquals(GameMode.SPECTATOR, players.get(CAROL).gameMode);

        matches.close();
        viewers.onMatchEnd(events(MatchEndEvent.class).getLast());
        assertFalse(viewers.contains(CAROL));
        assertEquals(GameMode.SURVIVAL, players.get(CAROL).gameMode);
        assertFalse(snapshots.exists(CAROL));
    }

    @Test
    void quitFreesTheSlotAndJoinRestoresThroughTheExistingListener() throws Exception {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        snapshots.save(snapshot(DAVE));
        viewers.arrived(player(DAVE), "dojo");
        assertTrue(viewers.contains(DAVE));

        var quit = new PlayerQuitEvent(player(DAVE), Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED);
        restoreListener.onQuit(quit);
        viewers.onQuit(quit);

        assertFalse(viewers.contains(DAVE));
        assertTrue(viewers.destination("dojo").isPresent());
        assertTrue(snapshots.exists(DAVE));

        restoreListener.onJoin(new PlayerJoinEvent(player(DAVE), Component.empty()));
        runNextTick();
        assertEquals(List.of(DAVE), restored);
        assertEquals(GameMode.SURVIVAL, players.get(DAVE).gameMode);
        assertFalse(snapshots.exists(DAVE));
    }

    private PlayerSnapshot snapshot(UUID id) {
        return new PlayerSnapshot(id, players.get(id).name, Instant.EPOCH,
                new SnapshotLocation(overworld.getUID(), "world", 100, 64, 100, 0, 0),
                new byte[0], new byte[0], new byte[0], 0, 0, 0, 20, 20, 0, 0,
                List.of(), GameMode.SURVIVAL, 0, 0);
    }
}
