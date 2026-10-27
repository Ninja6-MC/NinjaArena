package com.ninja6.ninjaarena;

import com.ninja6.ninjaarena.arena.ArenaProtectionListener;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.command.ArenaCommand;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.command.PortalCommand;
import com.ninja6.ninjaarena.match.MatchListener;
import com.ninja6.ninjaarena.match.MatchProtectionListener;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.MatchSettings;
import com.ninja6.ninjaarena.match.ScoringSettings;
import com.ninja6.ninjaarena.match.TeamScores;
import com.ninja6.ninjaarena.match.TeamScoringListener;
import com.ninja6.ninjaarena.portal.PortalEntry;
import com.ninja6.ninjaarena.portal.PortalListener;
import com.ninja6.ninjaarena.portal.PortalRegistry;
import com.ninja6.ninjaarena.restore.PlayerRestorer;
import com.ninja6.ninjaarena.restore.RestoreListener;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.waiting.WaitingRoomListener;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import com.ninja6.ninjaarena.waiting.WaitingRoomService.Exit;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import java.io.IOException;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for NinjaArena.
 */
public final class NinjaArenaPlugin extends JavaPlugin {

    private SnapshotService snapshots;
    private ArenaWorld arenaWorld;
    private RestoreService restores;
    private ArenaRegistry arenas;
    private PortalRegistry portals;
    private MatchService matches;
    private TeamScores scores;
    private WaitingRoomService waitingRooms;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            snapshots = new SnapshotService(
                    new SnapshotStore(getDataFolder().toPath().resolve("snapshots")), getLogger());
        } catch (IOException e) {
            // Without a snapshot store no player can enter an arena safely.
            getLogger().log(Level.SEVERE, "Could not open the snapshot store; disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        restores = new RestoreService(snapshots, new PlayerRestorer(getLogger()), getLogger());
        restores.markPendingOnStartup();
        getServer().getPluginManager().registerEvents(new RestoreListener(this, restores), this);
        arenaWorld = ArenaWorld.load(ArenaWorldSettings.from(getConfig().getConfigurationSection("arena-world")), getLogger());
        arenas = ArenaRegistry.open(this, arenaWorld);
        getServer().getPluginManager().registerEvents(new ArenaProtectionListener(arenas), this);
        portals = PortalRegistry.open(this);
        matches = new MatchService(this, arenas, arenaWorld::world,
                MatchSettings.from(getConfig().getConfigurationSection("match")), restores::restore,
                getServer().getPluginManager()::callEvent, MatchService::readyForRound);
        getServer().getPluginManager().registerEvents(new MatchListener(matches), this);
        getServer().getPluginManager().registerEvents(new MatchProtectionListener(matches,
                getServer()::getPlayer, getConfig().getBoolean("match.friendly-fire", false)), this);
        scores = new TeamScores();
        matches.setStandings(scores);
        getServer().getPluginManager().registerEvents(new TeamScoringListener(scores,
                ScoringSettings.from(getConfig().getConfigurationSection("scoring")), getServer()::getPlayer), this);
        matches.start();
        // A finished countdown hands its players to the match engine.
        waitingRooms = new WaitingRoomService(this, arenas,
                WaitingRoomSettings.from(getConfig().getConfigurationSection("waiting-room")), restores::restore,
                matches);
        getServer().getPluginManager().registerEvents(new WaitingRoomListener(waitingRooms), this);
        arenas.onEditOrDelete(waitingRooms::closeRoom);
        waitingRooms.start();
        PortalCommand portalCommand = new PortalCommand(portals, arenas, name -> getServer().getWorld(name) != null);
        PortalEntry entry = new PortalEntry(arenas, snapshots::hasPending, snapshots::captureAndPersist);
        getServer().getPluginManager().registerEvents(new PortalListener(this, portals, entry, arenaWorld::world,
                snapshots, portalCommand::forget, waitingRooms::join), this);
        NinjaArenaCommand command = new NinjaArenaCommand(new ArenaCommand(arenas, arenaWorld::world), portalCommand,
                player -> waitingRooms.leave(player, Exit.COMMAND)
                        || matches.leave(player, MatchService.Exit.COMMAND));
        getCommand("ninjaarena").setExecutor(command);
        getCommand("ninjaarena").setTabCompleter(command);
        getLogger().info("NinjaArena enabled.");
    }

    @Override
    public void onDisable() {
        if (waitingRooms != null) {
            waitingRooms.close();
            waitingRooms = null;
        }
        if (matches != null) {
            matches.close();
            matches = null;
        }
        scores = null;
        if (arenas != null) {
            arenas.close().forEach(getLogger()::warning);
            arenas = null;
        }
        if (arenaWorld != null) {
            arenaWorld.close();
        }
        portals = null;
        restores = null;
        snapshots = null;
        getLogger().info("NinjaArena disabled.");
    }

    /** Pre-entry player snapshots; {@code null} while the plugin is disabled. */
    public SnapshotService snapshots() {
        return snapshots;
    }

    /** Returns players home from their snapshot; {@code null} while the plugin is disabled. */
    public RestoreService restores() {
        return restores;
    }

    public ArenaWorld arenaWorld() {
        return arenaWorld;
    }

    /** Registered arenas; {@code null} while the plugin is disabled. */
    public ArenaRegistry arenas() {
        return arenas;
    }

    /** Waiting rooms and their start countdowns; {@code null} while the plugin is disabled. */
    public WaitingRoomService waitingRooms() {
        return waitingRooms;
    }

    /**
     * Running matches, their events' read API and the team ranking; {@code null} while the
     * plugin is disabled.
     */
    public MatchService matches() {
        return matches;
    }

    /** Team points and the award API for live matches; {@code null} while disabled. */
    public TeamScores scores() {
        return scores;
    }

    /** Registered portals; {@code null} while the plugin is disabled. */
    public PortalRegistry portals() {
        return portals;
    }
}
