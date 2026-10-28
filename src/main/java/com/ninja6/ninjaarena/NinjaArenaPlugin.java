package com.ninja6.ninjaarena;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaProtectionListener;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.command.ArenaCommand;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.command.PortalCommand;
import com.ninja6.ninjaarena.director.DirectorService;
import com.ninja6.ninjaarena.director.EnemyCosts;
import com.ninja6.ninjaarena.director.EnemyLifecycle;
import com.ninja6.ninjaarena.director.IntensitySettings;
import com.ninja6.ninjaarena.director.MobSpawner;
import com.ninja6.ninjaarena.director.ThreatBudget;
import com.ninja6.ninjaarena.kit.InventoryLockdownListener;
import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitInventory;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.kit.RoundKitListener;
import com.ninja6.ninjaarena.match.MatchDeathListener;
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
import com.ninja6.ninjaarena.tool.NinjaTools;
import com.ninja6.ninjaarena.tool.ToolSettings;
import com.ninja6.ninjaarena.waiting.WaitingRoomListener;
import com.ninja6.ninjaarena.waiting.WaitingRoomService.Exit;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaChunkUnloader;
import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import java.io.IOException;
import java.util.SplittableRandom;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for NinjaArena.
 */
public final class NinjaArenaPlugin extends JavaPlugin {

    private SnapshotService snapshots;
    private ArenaWorld arenaWorld;
    private ArenaChunkUnloader chunkUnloader;
    private RestoreService restores;
    private ArenaRegistry arenas;
    private PortalRegistry portals;
    private MatchService matches;
    private MatchDeathListener deaths;
    private TeamScores scores;
    private DirectorService directors;
    private EnemyLifecycle enemyLifecycle;
    private KitItems kitItems;
    private NinjaTools tools;
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
        chunkUnloader = new ArenaChunkUnloader(arenaWorld.world(), arenaWorld::isSavingEnabled, getLogger());
        chunkUnloader.start(this);
        getServer().getPluginManager().registerEvents(new ArenaProtectionListener(arenas), this);
        portals = PortalRegistry.open(this);
        matches = new MatchService(this, arenas, arenaWorld::world,
                MatchSettings.from(getConfig().getConfigurationSection("match")), restores::restore,
                getServer().getPluginManager()::callEvent, MatchService::readyForRound);
        deaths = new MatchDeathListener(this, matches, arenas);
        getServer().getPluginManager().registerEvents(deaths, this);
        getServer().getPluginManager().registerEvents(new MatchListener(matches, deaths), this);
        getServer().getPluginManager().registerEvents(new MatchProtectionListener(matches,
                getServer()::getPlayer, getConfig().getBoolean("match.friendly-fire", false), deaths::isSpectating), this);
        scores = new TeamScores();
        matches.setStandings(scores);
        getServer().getPluginManager().registerEvents(new TeamScoringListener(scores,
                ScoringSettings.from(getConfig().getConfigurationSection("scoring")), getServer()::getPlayer), this);
        // Starts and stops each match's spawn loop with its fights; closed before the matches.
        directors = new DirectorService(this,
                IntensitySettings.from(getConfig().getConfigurationSection("director.intensity")),
                ThreatBudget.from(getConfig().getConfigurationSection("director.threat-budget")),
                EnemyCosts.from(getConfig().getConfigurationSection("director.enemy-costs")),
                scores, matches::matchOf, MobSpawner.factory(arenas, arenaWorld::world, getServer()::getPlayer,
                        getLogger()), new SplittableRandom()::nextDouble);
        getServer().getPluginManager().registerEvents(directors, this);
        enemyLifecycle = new EnemyLifecycle(matches::matches, directors::director,
                name -> arenas.get(name).map(Arena::bounds), arenaWorld::world, scores,
                EnemyCosts.from(getConfig().getConfigurationSection("director.enemy-costs")), System::nanoTime);
        getServer().getPluginManager().registerEvents(enemyLifecycle, this);
        enemyLifecycle.start(this);
        kitItems = new KitItems();
        NinjaTools.registerItems(kitItems);
        tools = new NinjaTools(matches, getServer()::getPlayer, name -> arenas.get(name).map(Arena::bounds),
                ToolSettings.from(getConfig().getConfigurationSection("tools")), Bukkit::getCurrentTick,
                NinjaTools::damage, at -> at.getWorld().spawn(at, AreaEffectCloud.class));
        getServer().getPluginManager().registerEvents(tools, this);
        var kitFile = getDataFolder().toPath().resolve("kits.yml");
        var kit = KitDefinition.open(kitFile, getLogger(), () -> saveResource("kits.yml", false));
        getServer().getPluginManager().registerEvents(new RoundKitListener(matches, getServer()::getPlayer,
                kit, kitItems, getLogger()), this);
        matches.start();
        deaths.start();
        // A finished countdown hands its players to the match engine.
        waitingRooms = new WaitingRoomService(this, arenas,
                WaitingRoomSettings.from(getConfig().getConfigurationSection("waiting-room")), restores::restore,
                KitInventory::emptyForWaiting, matches);
        // Waiting players are locked like match players; the fields are cleared on disable.
        MatchService inMatch = matches;
        WaitingRoomService waiting = waitingRooms;
        getServer().getPluginManager().registerEvents(new InventoryLockdownListener(
                id -> inMatch.isInMatch(id) || waiting.isWaiting(id)), this);
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
        if (enemyLifecycle != null) {
            enemyLifecycle.close();
            enemyLifecycle = null;
        }
        if (directors != null) {
            directors.close();
            directors = null;
        }
        if (matches != null) {
            matches.close();
            matches = null;
        }
        if (tools != null) {
            tools.close();
            tools = null;
        }
        scores = null;
        kitItems = null;
        if (deaths != null) {
            deaths.close();
            deaths = null;
        }
        if (arenas != null) {
            arenas.close().forEach(getLogger()::warning);
            arenas = null;
        }
        if (chunkUnloader != null) {
            chunkUnloader.close();
            getLogger().info("Unloaded " + chunkUnloader.unloaded() + " arena world chunk(s) this run; "
                    + arenaWorld.world().getLoadedChunks().length + " still loaded at shutdown.");
            chunkUnloader = null;
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

    /**
     * The enemy director of each match, with the spawn gate and targeting hooks; {@code null}
     * while disabled.
     */
    public DirectorService directors() {
        return directors;
    }

    /** Register named providers for custom kit items before a round starts; null while disabled. */
    public KitItems kitItems() {
        return kitItems;
    }

    /** The ninja kit tools and the shuriken mark query; {@code null} while disabled. */
    public NinjaTools tools() {
        return tools;
    }

    /** Registered portals; {@code null} while the plugin is disabled. */
    public PortalRegistry portals() {
        return portals;
    }
}
