package com.ninja6.ninjaarena;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.ArenaProtectionListener;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.command.ArenaCommand;
import com.ninja6.ninjaarena.command.DirectorCommand;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.command.PortalCommand;
import com.ninja6.ninjaarena.config.ConfigReload;
import com.ninja6.ninjaarena.director.AggroSteering;
import com.ninja6.ninjaarena.director.DirectorService;
import com.ninja6.ninjaarena.director.EnemyCosts;
import com.ninja6.ninjaarena.director.EnemyLifecycle;
import com.ninja6.ninjaarena.director.IntensitySettings;
import com.ninja6.ninjaarena.director.MatchDirector;
import com.ninja6.ninjaarena.director.MobSpawner;
import com.ninja6.ninjaarena.director.SpawnSettings;
import com.ninja6.ninjaarena.director.StabilityGate;
import com.ninja6.ninjaarena.director.StabilitySettings;
import com.ninja6.ninjaarena.director.StealthSettings;
import com.ninja6.ninjaarena.director.ThreatBudget;
import com.ninja6.ninjaarena.director.WorldSenses;
import com.ninja6.ninjaarena.kit.InventoryLockdownListener;
import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitInventory;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.kit.RoundKitListener;
import com.ninja6.ninjaarena.match.MatchDeathListener;
import com.ninja6.ninjaarena.match.MatchHud;
import com.ninja6.ninjaarena.match.MatchListener;
import com.ninja6.ninjaarena.match.MatchProtectionListener;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.MatchSettings;
import com.ninja6.ninjaarena.match.ScoringSettings;
import com.ninja6.ninjaarena.match.TeamScores;
import com.ninja6.ninjaarena.match.TeamScoringListener;
import com.ninja6.ninjaarena.portal.ArenaAdmission;
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
import com.ninja6.ninjaarena.waiting.HostingService;
import com.ninja6.ninjaarena.waiting.WaitingRoomListener;
import com.ninja6.ninjaarena.waiting.WaitingRoomService.Exit;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaChunkUnloader;
import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import java.io.IOException;
import java.util.List;
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
    private MatchHud hud;
    private DirectorService directors;
    private EnemyLifecycle enemyLifecycle;
    private KitItems kitItems;
    private NinjaTools tools;
    private WaitingRoomService waitingRooms;
    private HostingService hosting;

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
        restores = new RestoreService(snapshots, new PlayerRestorer(getLogger(), () -> arenaWorld.world()), getLogger());
        restores.markPendingOnStartup();
        getServer().getPluginManager().registerEvents(new RestoreListener(this, restores), this);
        arenaWorld = ArenaWorld.load(ArenaWorldSettings.from(getConfig().getConfigurationSection("arena-world")), getLogger());
        arenas = ArenaRegistry.open(this, arenaWorld);
        chunkUnloader = new ArenaChunkUnloader(arenaWorld::currentWorld, arenaWorld::isSavingEnabled,
                () -> arenaWorld.releaseIfIdle(arenas::canReleaseWorld), getLogger());
        chunkUnloader.start(this);
        getServer().getPluginManager().registerEvents(new ArenaProtectionListener(arenas), this);
        portals = PortalRegistry.open(this);
        matches = new MatchService(this, arenas, arenaWorld::world,
                MatchSettings.from(getConfig().getConfigurationSection("match")), restores::restore,
                getServer().getPluginManager()::callEvent, MatchService::readyForRound);
        deaths = new MatchDeathListener(this, matches, arenas);
        getServer().getPluginManager().registerEvents(deaths, this);
        getServer().getPluginManager().registerEvents(new MatchListener(matches, deaths), this);
        MatchProtectionListener protection = new MatchProtectionListener(matches,
                getServer()::getPlayer, getConfig().getBoolean("match.friendly-fire", false), deaths::isSpectating);
        getServer().getPluginManager().registerEvents(protection, this);
        scores = new TeamScores();
        matches.setStandings(scores);
        TeamScoringListener scoring = new TeamScoringListener(scores,
                ScoringSettings.from(getConfig().getConfigurationSection("scoring")), getServer()::getPlayer);
        getServer().getPluginManager().registerEvents(scoring, this);
        // Starts and stops each match's spawn loop with its fights; closed before the matches.
        directors = new DirectorService(this,
                IntensitySettings.from(getConfig().getConfigurationSection("director.intensity")),
                ThreatBudget.from(getConfig().getConfigurationSection("director.threat-budget")),
                EnemyCosts.from(getConfig().getConfigurationSection("director.enemy-costs")),
                scores, matches::matchOf, MobSpawner.factory(arenas, arenaWorld::world, getServer()::getPlayer,
                        SpawnSettings.from(getConfig().getConfigurationSection("director")), getLogger()),
                new SplittableRandom()::nextDouble);
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
        // Stealth, smoke and shuriken marks steer the enemies' targets.
        DirectorService steered = directors;
        WorldSenses senses = new WorldSenses(getServer()::getPlayer, tools::inSmoke);
        AggroSteering steering = new AggroSteering(
                StealthSettings.from(getConfig().getConfigurationSection("director.stealth")), senses,
                tools::isMarked, match -> steered.director(match).map(MatchDirector::enemies).orElse(List.of()),
                Bukkit::getCurrentTick);
        directors.setWeigher(steering);
        getServer().getPluginManager().registerEvents(senses, this);
        getServer().getPluginManager().registerEvents(steering, this);
        var kitFile = getDataFolder().toPath().resolve("kits.yml");
        var kit = KitDefinition.open(kitFile, getLogger(), () -> saveResource("kits.yml", false));
        RoundKitListener roundKits = new RoundKitListener(matches, getServer()::getPlayer, kit, kitItems, getLogger());
        getServer().getPluginManager().registerEvents(roundKits, this);
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
        // Match slots, enemy caps, the spawn rate and the tick-time guard; enemies are counted
        // from each match's director. Set before any fight can create a director.
        StabilitySettings stability = StabilitySettings.from(getConfig().getConfigurationSection("stability"));
        DirectorService enemyDirectors = directors;
        hud = new MatchHud(inMatch::matches, getServer()::getPlayer, scores,
                match -> enemyDirectors.director(match).map(director -> (int) director.enemies().stream()
                        .filter(MatchDirector.Enemy::isAlive).count()).orElse(0));
        getServer().getPluginManager().registerEvents(hud, this);
        hud.start(this);
        directors.setGate(hud.withSpawnCues(new StabilityGate(stability,
                match -> enemyDirectors.director(match).map(StabilityGate::alive).orElse(0), inMatch::matches,
                Bukkit::getCurrentTick, Bukkit::getAverageTickTime, getLogger())));
        waitingRooms.setStartAllowed(() -> inMatch.matches().size() < stability.maxMatches());
        waitingRooms.start();
        PortalCommand portalCommand = new PortalCommand(portals, arenas, name -> getServer().getWorld(name) != null);
        PortalEntry entry = new PortalEntry(arenas, snapshots::hasPending, snapshots::captureAndPersist);
        ArenaAdmission admission = new ArenaAdmission(this, entry, arenaWorld::world, snapshots, waitingRooms::join);
        hosting = new HostingService(this, arenas, waitingRooms, entry, admission, arenaWorld::world,
                inMatch::isInMatch, arenaWorld::acquire);
        getServer().getPluginManager().registerEvents(hosting, this);
        getServer().getPluginManager().registerEvents(new PortalListener(this, portals, entry, arenaWorld::world,
                snapshots, portalCommand::forget, waitingRooms::join, arenaWorld::acquire), this);
        ConfigReload reloader = new ConfigReload(getDataFolder().toPath(), getConfig(), kit, kitItems,
                new ConfigReload.Target() {
                    @Override
                    public boolean canResize() {
                        return arenas.canResize() && inMatch.matches().isEmpty();
                    }

                    @Override
                    public void world(ArenaWorldSettings settings) {
                        arenaWorld.setDistances(settings.viewDistance(), settings.simulationDistance());
                    }

                    @Override
                    public void grid(int cellSize, double borderSize) {
                        if (!canResize()) {
                            throw new IllegalStateException("The arena grid is in use");
                        }
                        ArenaGrid old = arenas.grid();
                        ArenaGrid next = new ArenaGrid(cellSize, borderSize, old.minY(), old.maxY());
                        arenaWorld.setBorderSize(borderSize);
                        arenas.setGrid(next);
                    }

                    @Override
                    public void waiting(WaitingRoomSettings settings) {
                        waiting.setSettings(settings);
                    }

                    @Override
                    public void match(MatchSettings settings, boolean friendlyFire, ScoringSettings awards) {
                        protection.setFriendlyFire(friendlyFire);
                        scoring.setSettings(awards, inMatch.matches());
                        inMatch.setSettings(settings);
                    }

                    @Override
                    public void tools(ToolSettings settings) {
                        tools.setSettings(settings);
                    }

                    @Override
                    public void kit(KitDefinition definition) {
                        roundKits.setDefinition(definition);
                    }

                    @Override
                    public void configuration(org.bukkit.configuration.ConfigurationSection settings) {
                        for (String path : settings.getKeys(true)) {
                            if (!settings.isConfigurationSection(path)) {
                                getConfig().set(path, settings.get(path));
                            }
                        }
                    }
                });
        NinjaArenaCommand command = new NinjaArenaCommand(new ArenaCommand(arenas, arenaWorld::world,
                arenaWorld::acquire, task -> getServer().getScheduler().runTask(this, task)), portalCommand,
                new DirectorCommand(inMatch::matches, inMatch::matchOf, enemyDirectors::director),
                player -> {
                    boolean cancelled = hosting.leave(player);
                    return waitingRooms.leave(player, Exit.COMMAND)
                            || matches.leave(player, MatchService.Exit.COMMAND) || cancelled;
                }, hosting, reloader::execute);
        getCommand("ninjaarena").setExecutor(command);
        getCommand("ninjaarena").setTabCompleter(command);
        getLogger().info("NinjaArena enabled.");
    }

    @Override
    public void onDisable() {
        if (hosting != null) {
            hosting.close();
            hosting = null;
        }
        if (waitingRooms != null) {
            waitingRooms.close();
            waitingRooms = null;
        }
        if (hud != null) {
            hud.close();
            hud = null;
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
            arenaWorld.releaseIfIdle(arenas::canReleaseWorld);
            arenas = null;
        }
        if (chunkUnloader != null) {
            chunkUnloader.close();
            getLogger().info("Unloaded " + chunkUnloader.unloaded() + " arena world chunk(s) this run; "
                    + arenaWorld.currentWorld().map(world -> world.getLoadedChunks().length).orElse(0)
                    + " still loaded at shutdown.");
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
