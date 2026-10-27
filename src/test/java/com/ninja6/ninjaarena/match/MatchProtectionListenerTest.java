package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.PlayerRestorer;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.support.TestServer;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

class MatchProtectionListenerTest extends MatchFixture {

    private MatchProtectionListener protection(boolean enabled) {
        return new MatchProtectionListener(matches, id -> players.get(id).proxy, enabled, deaths::isSpectating);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if (values.containsKey(m.getName())) {
                return values.get(m.getName());
            }
            throw new UnsupportedOperationException(m.getName());
        });
    }

    private EntityDamageByEntityEvent damage(Entity attacker, Entity victim) {
        return new EntityDamageByEntityEvent(attacker, victim, DamageCause.ENTITY_ATTACK,
                proxy(DamageSource.class, java.util.Collections.singletonMap("getCausingEntity", null)), 5);
    }

    @Test
    void meleeAndProjectilesBlockOnlyTeammatesInTheSameMatch() {
        startTwoOnTwo();
        var protection = protection(false);
        var melee = damage(player(ALICE), player(CAROL));
        protection.onDamage(melee);
        assertTrue(melee.isCancelled());

        Projectile arrow = proxy(Projectile.class, Map.of("getShooter", player(ALICE)));
        var shot = damage(arrow, player(CAROL));
        protection.onDamage(shot);
        assertTrue(shot.isCancelled());

        for (var event : List.of(damage(player(ALICE), player(BOB)), damage(player(ALICE), player(ALICE)),
                damage(proxy(Entity.class, Map.of()), player(CAROL)))) {
            protection.onDamage(event);
            assertFalse(event.isCancelled());
        }
        matches.leave(player(CAROL), MatchService.Exit.COMMAND);
        var left = damage(arrow, player(CAROL));
        protection.onDamage(left);
        assertFalse(left.isCancelled());
        var outsider = damage(player(CAROL), player(ALICE));
        protection.onDamage(outsider);
        assertFalse(outsider.isCancelled());
    }

    @Test
    void identicalTeamNumbersInDifferentMatchesAreNotTeammates() throws Exception {
        arenas.create("other", new com.ninja6.ninjaarena.arena.GridCell(1, 0));
        arenas.update("other", a -> a.withBounds(new com.ninja6.ninjaarena.arena.ArenaBounds(266, 60, 10, 346, 100, 90))
                .withWaitingRoom(new com.ninja6.ninjaarena.arena.ArenaPoint(300, 64, 50, 0, 0))
                .withTeamSpawn(1, new com.ninja6.ninjaarena.arena.ArenaPoint(280, 64, 20, 0, 0))
                .withTeamSpawn(2, new com.ninja6.ninjaarena.arena.ArenaPoint(330, 64, 80, 0, 0))
                .withEnemySpawn(new com.ninja6.ninjaarena.arena.ArenaPoint(300, 64, 20, 0, 0)));
        arenas.finishEdit("other");
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        matches.start("other", List.of(List.of(CAROL), List.of(DAVE)));
        var event = damage(player(ALICE), player(CAROL));
        protection(false).onDamage(event);
        assertFalse(event.isCancelled());
    }

    private PotionSplashEvent splash(Player shooter, PotionEffectType effect) {
        ThrownPotion potion = proxy(ThrownPotion.class,
                Map.of("getShooter", shooter, "getEffects", List.of(new PotionEffect(effect, 100, 0))));
        Map<LivingEntity, Double> targets = new HashMap<>();
        targets.put(player(ALICE), 1.0);
        targets.put(player(CAROL), 0.75);
        targets.put(player(BOB), 0.5);
        return new PotionSplashEvent(potion, null, null, null, targets);
    }

    private AreaEffectCloudApplyEvent cloud(Player shooter, PotionEffectType effect) {
        AreaEffectCloud cloud = proxy(AreaEffectCloud.class,
                Map.of("getSource", shooter, "getCustomEffects", List.of(new PotionEffect(effect, 100, 0))));
        // No base type: these clouds carry custom effects only.
        AreaEffectCloud withBase = (AreaEffectCloud) Proxy.newProxyInstance(AreaEffectCloud.class.getClassLoader(),
                new Class<?>[] {AreaEffectCloud.class}, (p, m, a) -> m.getName().equals("getBasePotionType")
                        ? null : m.invoke(cloud, a));
        return new AreaEffectCloudApplyEvent(withBase,
                new ArrayList<>(List.of(player(ALICE), player(CAROL), player(BOB))));
    }

    @Test
    void harmfulSplashesAndCloudsPreserveSelfEnemiesAndOutsiders() {
        startTwoOnTwo();
        var protection = protection(false);
        for (var effect : List.of(PotionEffectType.INSTANT_DAMAGE, PotionEffectType.POISON)) {
            var splash = splash(player(ALICE), effect);
            protection.onSplash(splash);
            assertEquals(0, splash.getIntensity(player(CAROL)));
            assertEquals(1, splash.getIntensity(player(ALICE)));
            assertEquals(0.5, splash.getIntensity(player(BOB)));
            assertFalse(splash.isCancelled());
            var cloud = cloud(player(ALICE), effect);
            protection.onCloud(cloud);
            assertEquals(List.of(player(ALICE), player(BOB)), cloud.getAffectedEntities());
            assertFalse(cloud.isCancelled());
        }
        matches.leave(player(CAROL), MatchService.Exit.COMMAND);
        var splash = splash(player(ALICE), PotionEffectType.POISON);
        protection.onSplash(splash);
        assertEquals(0.75, splash.getIntensity(player(CAROL)));
        var cloud = cloud(player(CAROL), PotionEffectType.POISON);
        protection.onCloud(cloud);
        assertEquals(3, cloud.getAffectedEntities().size());
    }

    @Test
    void enablingFriendlyFireAllowsAllThreeDamagePaths() {
        startTwoOnTwo();
        var protection = protection(true);
        var melee = damage(player(ALICE), player(CAROL));
        protection.onDamage(melee);
        assertFalse(melee.isCancelled());
        var splash = splash(player(ALICE), PotionEffectType.POISON);
        protection.onSplash(splash);
        assertEquals(0.75, splash.getIntensity(player(CAROL)));
        var cloud = cloud(player(ALICE), PotionEffectType.POISON);
        protection.onCloud(cloud);
        assertEquals(3, cloud.getAffectedEntities().size());
    }

    @Test
    void beneficialEffectsStillReachTeammates() {
        startTwoOnTwo();
        var protection = protection(false);
        var splash = splash(player(ALICE), PotionEffectType.SPEED);
        protection.onSplash(splash);
        assertEquals(0.75, splash.getIntensity(player(CAROL)));
        var cloud = cloud(player(ALICE), PotionEffectType.INSTANT_HEALTH);
        protection.onCloud(cloud);
        assertEquals(3, cloud.getAffectedEntities().size());
    }

    @Test
    void roundStartsSetAdventureOnlyForMatchPlayersAndLeavingAllowsRestoration() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        var protection = protection(false);
        protection.onRoundStart(events(RoundStartEvent.class).getFirst());
        assertEquals(GameMode.ADVENTURE, player(ALICE).getGameMode());
        assertEquals(GameMode.ADVENTURE, player(BOB).getGameMode());
        assertEquals(GameMode.SURVIVAL, player(CAROL).getGameMode());
        var change = new PlayerGameModeChangeEvent(player(ALICE), GameMode.CREATIVE,
                PlayerGameModeChangeEvent.Cause.PLUGIN, null);
        protection.onGameMode(change);
        assertTrue(change.isCancelled());
        matches.leave(player(ALICE), MatchService.Exit.COMMAND);
        var restore = new PlayerGameModeChangeEvent(player(ALICE), GameMode.SURVIVAL,
                PlayerGameModeChangeEvent.Cause.PLUGIN, null);
        protection.onGameMode(restore);
        assertFalse(restore.isCancelled());
    }

    @Test
    void basePotionCloudsAreFilteredToo() {
        TestServer.install();
        startTwoOnTwo();
        AreaEffectCloud cloud = proxy(AreaEffectCloud.class, Map.of("getSource", player(ALICE),
                "getBasePotionType", PotionType.POISON, "getCustomEffects", List.of()));
        var event = new AreaEffectCloudApplyEvent(cloud, new ArrayList<>(List.of(player(CAROL), player(BOB))));
        protection(false).onCloud(event);
        assertEquals(List.of(player(BOB)), event.getAffectedEntities());
    }

    @Test
    void indirectDamageUsesTheCausingPlayer() {
        startTwoOnTwo();
        var event = new EntityDamageByEntityEvent(proxy(Entity.class, Map.of()), player(CAROL),
                DamageCause.ENTITY_EXPLOSION, proxy(DamageSource.class, Map.of("getCausingEntity", player(ALICE))), 5);
        protection(false).onDamage(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void respawnsSetAdventureForMatchPlayersOnly() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        deaths.onPostRespawn(new PlayerPostRespawnEvent(player(ALICE), player(ALICE).getLocation(), false,
                false, false, PlayerRespawnEvent.RespawnReason.DEATH));
        assertEquals(GameMode.ADVENTURE, player(ALICE).getGameMode());
        deaths.onPostRespawn(new PlayerPostRespawnEvent(player(CAROL), player(CAROL).getLocation(), false,
                false, false, PlayerRespawnEvent.RespawnReason.DEATH));
        assertEquals(GameMode.SURVIVAL, player(CAROL).getGameMode());
    }

    @Test
    void leavingRestoresThePersistedGameModeThroughTheExistingRestorer() throws Exception {
        TestServer.install();
        Logger logger = Logger.getAnonymousLogger();
        var snapshots = new SnapshotService(new SnapshotStore(dir.resolve("snapshots")), logger);
        byte[] emptyItems = {1, 0, 0, 0, 0};
        for (var mode : List.of(GameMode.CREATIVE, GameMode.SURVIVAL, GameMode.ADVENTURE, GameMode.SPECTATOR)) {
            var snapshot = new PlayerSnapshot(ALICE, "Alice", Instant.now(),
                    new SnapshotLocation(new java.util.UUID(0, 99), "world", 0.5, 64, 0.5, 0, 0),
                    emptyItems, emptyItems, emptyItems, 0, 0, 0, 20, 20, 5, 0, List.of(), mode, 0, 0);
            snapshots.persist(snapshot);
            var restores = new RestoreService(snapshots, new PlayerRestorer(logger), logger);
            matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, restores::restore,
                    events::add, player -> { });
            var protection = protection(false);
            gameModeChanges = protection::onGameMode;
            matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
            protection.onRoundStart(new RoundStartEvent(matches.matchOf(ALICE).orElseThrow(), 1, false));
            assertEquals(GameMode.ADVENTURE, player(ALICE).getGameMode());
            assertTrue(matches.leave(player(ALICE), MatchService.Exit.COMMAND));
            assertEquals(mode, player(ALICE).getGameMode());
            assertFalse(snapshots.hasPending(ALICE));
            matches.close();
        }
    }

    @Test
    void closingAMatchRestoresThePersistedGameMode() throws Exception {
        TestServer.install();
        Logger logger = Logger.getAnonymousLogger();
        var snapshots = new SnapshotService(new SnapshotStore(dir.resolve("snapshots")), logger);
        byte[] emptyItems = {1, 0, 0, 0, 0};
        snapshots.persist(new PlayerSnapshot(ALICE, "Alice", Instant.now(),
                new SnapshotLocation(new java.util.UUID(0, 99), "world", 0.5, 64, 0.5, 0, 0),
                emptyItems, emptyItems, emptyItems, 0, 0, 0, 20, 20, 5, 0, List.of(), GameMode.CREATIVE, 0, 0));
        var restores = new RestoreService(snapshots, new PlayerRestorer(logger), logger);
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, restores::restore,
                events::add, player -> { });
        var protection = protection(false);
        gameModeChanges = protection::onGameMode;
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        protection.onRoundStart(new RoundStartEvent(matches.matchOf(ALICE).orElseThrow(), 1, false));
        assertEquals(GameMode.ADVENTURE, player(ALICE).getGameMode());

        // Every match end sends players home through the same close path.
        matches.close();
        assertFalse(matches.isInMatch(ALICE));
        assertEquals(GameMode.CREATIVE, player(ALICE).getGameMode());
        assertFalse(snapshots.hasPending(ALICE));
    }

    @Test
    void bundledConfigDisablesFriendlyFireByDefault() {
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        assertTrue(config.contains("match.friendly-fire"));
        assertFalse(config.getBoolean("match.friendly-fire"));
    }
}
