package com.ninja6.ninjaarena;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.ArenaProtectionListener;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.command.ArenaCommand;
import com.ninja6.ninjaarena.command.DirectorCommand;
import com.ninja6.ninjaarena.command.EventCommand;
import com.ninja6.ninjaarena.event.ArenaEvents;
import com.ninja6.ninjaarena.event.EventSettings;
import com.ninja6.ninjaarena.event.EventWebhook;
import com.ninja6.ninjaarena.command.LeaderboardCommand;
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
import com.ninja6.ninjaarena.director.ShadowStalker;
import com.ninja6.ninjaarena.director.SpawnSettings;
import com.ninja6.ninjaarena.director.StabilityGate;
import com.ninja6.ninjaarena.director.StabilitySettings;
import com.ninja6.ninjaarena.director.StalkerSettings;
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
import com.ninja6.ninjaarena.portal.LobbyMode;
import com.ninja6.ninjaarena.portal.ParticleCurtain;
import com.ninja6.ninjaarena.portal.PlayerPortalListener;
import com.ninja6.ninjaarena.portal.PlayerPortalSettings;
import com.ninja6.ninjaarena.portal.PlayerPortals;
import com.ninja6.ninjaarena.portal.PortalCurtains;
import com.ninja6.ninjaarena.portal.PortalEntry;
import com.ninja6.ninjaarena.portal.PortalListener;
import com.ninja6.ninjaarena.portal.PortalRegistry;
import com.ninja6.ninjaarena.restore.PlayerRestorer;
import com.ninja6.ninjaarena.restore.ArenaCombatCleanup;
import com.ninja6.ninjaarena.restore.RestoreListener;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.spectate.SpectatorSettings;
import com.ninja6.ninjaarena.spectate.Spectators;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.placeholder.NinjaArenaExpansion;
import com.ninja6.ninjaarena.placeholder.StatsPlaceholders;
import com.ninja6.ninjaarena.reward.RewardSettings;
import com.ninja6.ninjaarena.reward.Rewards;
import com.ninja6.ninjaarena.stats.StatsSettings;
import com.ninja6.ninjaarena.stats.StatsStore;
import com.ninja6.ninjaarena.stats.StatsTracker;
import com.ninja6.ninjaarena.tool.NinjaTools;
import com.ninja6.ninjaarena.tool.ToolSettings;
import com.ninja6.ninjaarena.tool.ToolVisuals;
import com.ninja6.ninjaarena.resourcepack.BukkitResourcePacks;
import com.ninja6.ninjaarena.resourcepack.ResourcePackSettings;
import com.ninja6.ninjaarena.resourcepack.ResourcePacks;
import com.ninja6.ninjaarena.resourcepack.BedrockIntegration;
import com.ninja6.ninjaarena.resourcepack.BedrockPackSettings;
import com.ninja6.ninjaarena.resourcepack.BedrockSelections;
import com.ninja6.ninjaarena.resourcepack.BedrockSupport;
import com.ninja6.ninjaarena.waiting.HostingService;
import com.ninja6.ninjaarena.waiting.WaitingRoomListener;
import com.ninja6.ninjaarena.waiting.WaitingRoomService.Exit;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaChunkUnloader;
import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import com.ninja6.ninjaarena.world.ArenaExtentListener;
import com.ninja6.ninjaarena.world.ArenaExtent;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.List;
import java.util.Optional;
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
    private volatile ArenaWorld arenaWorld;
    private ArenaChunkUnloader chunkUnloader;
    private RestoreService restores;
    private Spectators spectators;
    private ArenaRegistry arenas;
    private PortalRegistry portals;
    private PortalCurtains curtains;
    private MatchService matches;
    private MatchDeathListener deaths;
    private TeamScores scores;
    private MatchHud hud;
    private DirectorService directors;
    private EnemyLifecycle enemyLifecycle;
    private KitItems kitItems;
    private NinjaTools tools;
    private BukkitResourcePacks resourcePacks;
    private ResourcePackSettings activePackSettings = ResourcePackSettings.OFF;
    private BedrockIntegration bedrock = BedrockIntegration.NONE;
    private WaitingRoomService waitingRooms;
    private HostingService hosting;
    private StatsStore stats;
    private Rewards rewards;
    private StatsPlaceholders placeholders;
    private Runnable unregisterPlaceholders;

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
        restores = new RestoreService(snapshots, new PlayerRestorer(getLogger(), () -> arenaWorld.world(),
                (x, z) -> arenaWorld != null && ArenaExtent.contains(x, z, arenaWorld.borderSize())), getLogger(),
                new ArenaCombatCleanup(getLogger()));
        restores.markPendingOnStartup();
        getServer().getPluginManager().registerEvents(new RestoreListener(this, restores), this);
        getServer().getPluginManager().registerEvents(new ArenaExtentListener(() -> arenaWorld == null
                ? ArenaWorldSettings.from(getConfig().getConfigurationSection("arena-world")).borderSize()
                : arenaWorld.borderSize(), getServer().getWorlds().getFirst().getSpawnLocation()), this);
        getServer().getPluginManager().registerEvents(new ArenaProtectionListener(
                () -> arenas == null || arenas.editing().isEmpty()), this);
        arenaWorld = ArenaWorld.load(ArenaWorldSettings.from(getConfig().getConfigurationSection("arena-world")), getLogger());
        arenas = ArenaRegistry.open(this, arenaWorld);
        chunkUnloader = new ArenaChunkUnloader(arenaWorld::currentWorld, arenaWorld::isSavingEnabled,
                () -> arenaWorld.releaseIfIdle(arenas::canReleaseWorld), getLogger());
        chunkUnloader.start(this);
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
        StalkerSettings stalker = StalkerSettings.from(getConfig().getConfigurationSection("director.shadow-stalker"));
        directors = new DirectorService(this,
                IntensitySettings.from(getConfig().getConfigurationSection("director.intensity")),
                ThreatBudget.from(getConfig().getConfigurationSection("director.threat-budget")),
                EnemyCosts.from(getConfig().getConfigurationSection("director.enemy-costs")),
                scores, matches::matchOf, MobSpawner.factory(arenas, arenaWorld::world, getServer()::getPlayer,
                        SpawnSettings.from(getConfig().getConfigurationSection("director")),
                        new ShadowStalker(stalker, getServer().getMobGoals(), Bukkit::getCurrentTick), getLogger()),
                new SplittableRandom()::nextDouble, stalker);
        getServer().getPluginManager().registerEvents(directors, this);
        enemyLifecycle = new EnemyLifecycle(matches::matches, directors::director,
                name -> arenas.get(name).map(Arena::bounds), arenaWorld::world, scores,
                EnemyCosts.from(getConfig().getConfigurationSection("director.enemy-costs")), System::nanoTime);
        getServer().getPluginManager().registerEvents(enemyLifecycle, this);
        enemyLifecycle.start(this);
        startStats(enemyLifecycle);
        startPlaceholders();
        ResourcePackSettings packSettings;
        try {
            if (getConfig().contains("resource-pack") && !getConfig().isConfigurationSection("resource-pack")) {
                throw new IllegalArgumentException("resource-pack must be a section");
            }
            packSettings = ResourcePackSettings.from(getConfig().getConfigurationSection("resource-pack"));
        } catch (IllegalArgumentException e) {
            getLogger().severe("Resource pack delivery is off: " + e.getMessage()
                    + ". Fix resource-pack in config.yml and restart. Players keep vanilla visuals.");
            packSettings = ResourcePackSettings.OFF;
        }
        BedrockPackSettings bedrockSettings;
        try {
            bedrockSettings = packSettings.enabled()
                    ? BedrockPackSettings.from(getConfig().getConfigurationSection("resource-pack")) : BedrockPackSettings.OFF;
        } catch (IllegalArgumentException e) {
            getLogger().severe("Bedrock pack delivery is off: " + e.getMessage() + ". Fix config.yml and restart.");
            bedrockSettings = BedrockPackSettings.OFF;
        }
        try {
            bedrock = startBedrock(bedrockSettings);
        } catch (LinkageError | RuntimeException e) {
            getLogger().severe("Resource pack delivery is off: the same-server Geyser API could not be initialized ("
                    + e.getClass().getSimpleName() + "). Check the supported Geyser version and restart.");
            packSettings = ResourcePackSettings.OFF;
        }
        activePackSettings = packSettings;
        getLogger().info(packStatus());
        resourcePacks = new BukkitResourcePacks(this, packSettings, bedrock::isBedrock);
        resourcePacks.start();
        ToolVisuals visuals = new ToolVisuals(resourcePacks.packs(),
                id -> bedrock.isBedrock(id) ? bedrock.customHeld(id) : resourcePacks.packs().loaded(id),
                () -> getConfig().getBoolean("resource-pack.thrown-visuals", true),
                name -> spectators == null ? List.of() : spectators.players(name), matches::matchOf);
        kitItems = new KitItems();
        NinjaTools.registerItems(kitItems, visuals);
        tools = new NinjaTools(matches, getServer()::getPlayer, name -> arenas.get(name).map(Arena::bounds),
                ToolSettings.from(getConfig().getConfigurationSection("tools")), Bukkit::getCurrentTick,
                NinjaTools::damage, at -> at.getWorld().spawn(at, AreaEffectCloud.class));
        tools.setVisuals(visuals);
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
        String lobby = getConfig().getString("waiting-room.lobby");
        if (lobby != null && LobbyMode.parse(lobby).isEmpty()) {
            getLogger().warning("waiting-room.lobby must be dedicated or shared, not " + lobby
                    + "; portals use dedicated waiting rooms until it is fixed.");
        }
        waitingRooms = new WaitingRoomService(this, arenas,
                WaitingRoomSettings.from(getConfig().getConfigurationSection("waiting-room")), restores::restore,
                KitInventory::emptyForWaiting, matches);
        SpectatorSettings spectating;
        try {
            if (!getConfig().isConfigurationSection("spectating")) {
                throw new IllegalArgumentException("spectating must be a section");
            }
            spectating = SpectatorSettings.from(getConfig().getConfigurationSection("spectating"));
        } catch (IllegalArgumentException e) {
            getLogger().warning("Spectating disabled: " + e.getMessage());
            spectating = new SpectatorSettings(false, 16);
        }
        spectators = new Spectators(this, arenas, matches, waitingRooms, restores, arenaWorld::world, spectating);
        Spectators watching = spectators;
        getServer().getPluginManager().registerEvents(watching, this);
        arenas.onEditOrDelete(watching::closeArena);
        // Waiting players are locked like match players; the fields are cleared on disable.
        MatchService inMatch = matches;
        WaitingRoomService waiting = waitingRooms;
        getServer().getPluginManager().registerEvents(new InventoryLockdownListener(
                id -> inMatch.isInMatch(id) || waiting.isWaiting(id) || watching.contains(id)), this);
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
        ArenaEvents arenaEvents = startEvents();
        waitingRooms.setEventArena(arenaEvents::arena);
        matches.setEventBonus(arenaEvents::bonus);
        waitingRooms.start();
        // Each portal leads to its arena's waiting room or the shared hub; the default is read
        // from the current settings, so a reload changes it for the next entry.
        PortalRegistry portalList = portals;
        waitingRooms.setHub(portalList::hub);
        PortalCommand portalCommand = new PortalCommand(portals, arenas, name -> getServer().getWorld(name) != null,
                () -> waiting.settings().lobby());
        PortalEntry entry = new PortalEntry(arenas, snapshots::hasPending, snapshots::captureAndPersist, watching::destination);
        entry.setLobbies(() -> waiting.settings().lobby(), portalList::hub, waiting::full);
        ArenaAdmission admission = new ArenaAdmission(this, entry, arenaWorld::world, snapshots, waitingRooms::join,
                waitingRooms::joinHub);
        hosting = new HostingService(this, arenas, waitingRooms, entry, admission, arenaWorld::world,
                inMatch::isInMatch, arenaWorld::acquire);
        getServer().getPluginManager().registerEvents(hosting, this);
        getServer().getPluginManager().registerEvents(new PortalListener(this, portals, entry, arenaWorld::world,
                admission, portalCommand::forget, arenaWorld::acquire, watching::arrived), this);
        // Player-built portals share the registry and the entry above; under the dedicated lobby
        // each entry is routed to a filling waiting room or a free arena.
        entry.setRouting(waiting::openArena);
        PlayerPortalSettings building;
        try {
            building = PlayerPortalSettings.from(getConfig().getConfigurationSection(PlayerPortalSettings.SECTION));
        } catch (IllegalArgumentException e) {
            getLogger().warning("Building portals is off: " + e.getMessage() + ". Portals already built still work;"
                    + " fix config.yml and reload.");
            building = PlayerPortalSettings.OFF;
        }
        ParticleCurtain curtain = new ParticleCurtain(building.color(), new SplittableRandom());
        PlayerPortals playerPortals = new PlayerPortals(portals, building, getLogger());
        getServer().getPluginManager().registerEvents(new PlayerPortalListener(playerPortals, portals), this);
        curtains = new PortalCurtains(portals, playerPortals, getServer()::getOnlinePlayers, getServer()::getWorld,
                curtain, Bukkit::getCurrentTick);
        curtains.start(this);
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
                    public void spectating(SpectatorSettings settings) {
                        watching.setSettings(settings);
                    }

                    @Override
                    public void playerPortals(PlayerPortalSettings settings) {
                        playerPortals.setSettings(settings);
                        curtain.setColor(settings.color());
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
                    public void season(String name) {
                        if (stats != null) {
                            stats.useSeason(name);
                        }
                        if (placeholders != null) {
                            placeholders.invalidateSeason();
                        }
                    }

                    @Override
                    public boolean eventArenaExists(String arena) {
                        return arenas.get(arena).isPresent();
                    }

                    @Override
                    public void bedrockEnforcement() {
                        bedrock.refreshEnforcement();
                    }

                    @Override
                    public void configuration(org.bukkit.configuration.ConfigurationSection settings) {
                        if (placeholders != null) {
                            placeholders.setCacheSeconds(settings.getInt(StatsPlaceholders.CACHE_SECONDS));
                        }
                        if (rewards != null) {
                            rewards.settings(RewardSettings.from(settings));
                        }
                        arenaEvents.settings(EventSettings.from(settings, RewardSettings.from(settings)));
                        for (String path : settings.getKeys(true)) {
                            if (!settings.isConfigurationSection(path)) {
                                getConfig().set(path, settings.get(path));
                            }
                        }
                    }
                });
        ArenaCommand arenaCommand = new ArenaCommand(arenas, arenaWorld::world, arenaWorld::acquire,
                task -> getServer().getScheduler().runTask(this, task));
        arenaCommand.setHub(() -> portals == null ? Optional.empty() : portals.hub());
        NinjaArenaCommand command = new NinjaArenaCommand(arenaCommand, portalCommand,
                new DirectorCommand(inMatch::matches, inMatch::matchOf, enemyDirectors::director),
                player -> {
                    boolean cancelled = hosting.leave(player);
                    return watching.leave(player) || waitingRooms.leave(player, Exit.COMMAND)
                            || matches.leave(player, MatchService.Exit.COMMAND) || cancelled;
                }, hosting, reloader::execute, watching::fromWaitingRoom, new LeaderboardCommand(this, this::stats),
                new EventCommand(arenaEvents));
        command.setPackStatus(this::packStatus);
        getCommand("ninjaarena").setExecutor(command);
        getCommand("ninjaarena").setTabCompleter(command);
        getLogger().info("NinjaArena enabled.");
    }

    private ArenaEvents startEvents() {
        EventSettings settings;
        try {
            settings = EventSettings.from(getConfig(), RewardSettings.from(getConfig()));
            for (EventSettings.Night night : settings.schedule()) {
                if (arenas.get(night.arena()).isEmpty()) {
                    throw new IllegalArgumentException("unknown arena " + night.arena() + " for event " + night.id());
                }
            }
        } catch (IllegalArgumentException e) {
            getLogger().warning("Arena events disabled: " + e.getMessage());
            settings = EventSettings.OFF;
        }
        EventWebhook webhook = EventWebhook.create(getLogger()::warning);
        ArenaEvents events = new ArenaEvents(settings, Clock.systemUTC(), (window, minutes, url) -> {
            String path = minutes == 0 ? "events.messages.start" : "events.messages.upcoming";
            String message = getConfig().getString(path, "")
                    .replace("{id}", window.night().id())
                    .replace("{arena}", window.night().arena())
                    .replace("{minutes}", Integer.toString(minutes));
            getServer().broadcast(Component.text(message, NamedTextColor.GOLD));
            webhook.post(url, message);
        });
        getServer().getScheduler().runTaskTimer(this, events::tick, 20, 20);
        return events;
    }

    /**
     * Records player stats from the match events. A bad stats setting or an unreachable
     * database turns stats off or delays the writes; it never stops the plugin.
     */
    private void startStats(EnemyLifecycle enemies) {
        StatsSettings settings;
        try {
            settings = StatsSettings.from(getConfig().getConfigurationSection("stats"));
        } catch (IllegalArgumentException e) {
            getLogger().severe("Stats are off: " + e.getMessage() + ". Fix config.yml and restart to record stats.");
            return;
        }
        if (!settings.enabled()) {
            return;
        }
        stats = StatsStore.open(settings, getDataFolder().toPath(), getLogger());
        StatsTracker tracker = new StatsTracker(record -> {
            stats.record(record);
            if (rewards != null) {
                rewards.onRecorded(record);
            }
        }, getServer()::getPlayer, Instant::now);
        getServer().getPluginManager().registerEvents(tracker, this);
        enemies.setKills(tracker::enemyKilled);
        RewardSettings rewardSettings;
        try {
            rewardSettings = RewardSettings.from(getConfig());
        } catch (IllegalArgumentException e) {
            getLogger().warning("Reward configuration ignored: " + e.getMessage());
            rewardSettings = RewardSettings.OFF;
        }
        rewards = new Rewards(this, arenaWorld::world, rewardSettings);
    }

    private void startPlaceholders() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        Object configured = getConfig().get(StatsPlaceholders.CACHE_SECONDS);
        int seconds = StatsPlaceholders.DEFAULT_SECONDS;
        if (configured instanceof Integer value
                && value >= StatsPlaceholders.MIN_SECONDS && value <= StatsPlaceholders.MAX_SECONDS) {
            seconds = value;
        } else {
            getLogger().warning(StatsPlaceholders.CACHE_SECONDS + " must be an integer from "
                    + StatsPlaceholders.MIN_SECONDS + " to " + StatsPlaceholders.MAX_SECONDS + ". Using " + seconds + ".");
        }
        placeholders = new StatsPlaceholders(stats, seconds);
        unregisterPlaceholders = NinjaArenaExpansion.install(this, placeholders);
    }

    @Override
    public void onDisable() {
        if (resourcePacks != null) {
            resourcePacks.close();
            resourcePacks = null;
        }
        bedrock.close();
        bedrock = BedrockIntegration.NONE;
        if (rewards != null) {
            rewards.close();
            rewards = null;
        }
        if (unregisterPlaceholders != null) {
            unregisterPlaceholders.run();
            unregisterPlaceholders = null;
            placeholders = null;
        }
        if (curtains != null) {
            curtains.close();
            curtains = null;
        }
        if (spectators != null) {
            spectators.close();
            spectators = null;
        }
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
        // After the matches: stopping them records what they had so far.
        if (stats != null) {
            stats.close();
            stats = null;
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

    /**
     * Waiting rooms, the shared hub and their start countdowns; {@code null} while the plugin
     * is disabled.
     */
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

    /** Main-thread pack API for explicit Detailed selection; never replaces another plugin's packs. */
    public ResourcePacks resourcePacks() {
        return resourcePacks == null ? null : resourcePacks.packs();
    }

    private String packStatus() {
        return com.ninja6.ninjaarena.resourcepack.PackStatus.describe(activePackSettings, bedrock.status());
    }

    private BedrockIntegration startBedrock(BedrockPackSettings settings) {
        return BedrockSupport.start(getServer().getPluginManager().isPluginEnabled("Geyser-Spigot"),
                () -> new com.ninja6.ninjaarena.resourcepack.geyser.GeyserPackHook(this, settings));
    }

    /** Connection-time, XUID-based selection seam for #80; absent without configured same-server Geyser delivery. */
    public Optional<BedrockSelections> bedrockSelections() {
        return bedrock.selections();
    }

    /**
     * Player stats and seasons: reads for leaderboards and rewards, and season changes.
     * {@code null} while the plugin is disabled or stats are off in {@code config.yml}.
     */
    public StatsStore stats() {
        return stats;
    }

    /** Registered portals; {@code null} while the plugin is disabled. */
    public PortalRegistry portals() {
        return portals;
    }
}
