package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.kit.InventoryLockdownListener;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.support.TestTaggedItem;
import com.ninja6.ninjaarena.tool.NinjaTool;
import com.ninja6.ninjaarena.tool.NinjaTools;
import com.ninja6.ninjaarena.tool.ToolSettings;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NinjaToolsTest extends MatchFixture {

    private static final UUID EVE = new UUID(0, 5);

    /** An entity the tools spawned or tracked: what was set on it and whether it was removed. */
    private static final class Spawned {
        final UUID id = UUID.randomUUID();
        final Map<String, Object> values = new HashMap<>();
        final List<PotionEffect> effects = new ArrayList<>();
        boolean removed;
        Object proxy;
    }

    private record Launch(PlayerLaunchProjectileEvent event, Spawned projectile) {
    }

    private record Strike(LivingEntity victim, double damage, Projectile shuriken, Player thrower) {
    }

    private long tick;
    private NinjaTools tools;
    private MatchProtectionListener protection;
    private final List<Spawned> clouds = new ArrayList<>();
    private final List<Strike> strikes = new ArrayList<>();

    @BeforeEach
    void tools() {
        add(EVE, "Eve");
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, event -> {
            events.add(event);
            switch (event) {
                case RoundEndEvent ended -> tools.onRoundEnd(ended);
                case MatchEndEvent ended -> tools.onMatchEnd(ended);
                case PlayerEliminatedEvent out -> tools.onEliminated(out);
                case PlayerLeaveMatchEvent left -> tools.onLeave(left);
                default -> { }
            }
        }, player -> readied.add(player.getUniqueId()));
        deaths = new MatchDeathListener(plugin, matches, arenas);
        listener = new MatchListener(matches, deaths);
        protection = new MatchProtectionListener(matches, plugin.getServer()::getPlayer, false, deaths::isSpectating);
        tools = new NinjaTools(matches, plugin.getServer()::getPlayer, name -> arenas.get(name).map(Arena::bounds),
                ToolSettings.DEFAULTS, () -> tick, this::strike, at -> {
                    Spawned cloud = spawn(AreaEffectCloud.class, at, null);
                    clouds.add(cloud);
                    return (AreaEffectCloud) cloud.proxy;
                });
    }

    /** The server's damage path in miniature: shelter, out-of-round and friendly-fire rules, then the mark. */
    private void strike(LivingEntity victim, double damage, Projectile shuriken, Player thrower) {
        strikes.add(new Strike(victim, damage, shuriken, thrower));
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                new Class<?>[] {DamageSource.class}, (p, m, a) -> switch (m.getName()) {
                    case "getCausingEntity" -> thrower;
                    case "getDirectEntity" -> shuriken;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        var event = new EntityDamageByEntityEvent(shuriken, victim, DamageCause.PROJECTILE, source, damage);
        listener.onDamage(event);
        if (!event.isCancelled()) {
            listener.onAttack(event);
        }
        if (!event.isCancelled()) {
            protection.onDamage(event);
        }
        if (!event.isCancelled()) {
            tools.onShurikenDamage(event);
        }
    }

    private Spawned spawn(Class<?> type, Location at, Player shooter) {
        Spawned spawned = new Spawned();
        spawned.proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            switch (m.getName()) {
                case "getUniqueId":
                    return spawned.id;
                case "remove":
                    spawned.removed = true;
                    return null;
                case "isValid":
                    return !spawned.removed;
                case "getLocation":
                    return at.clone();
                case "getShooter":
                    return shooter;
                case "addCustomEffect":
                    return spawned.effects.add((PotionEffect) a[0]);
                case "hashCode":
                    return spawned.id.hashCode();
                case "equals":
                    return p == a[0];
                default:
                    if (m.getName().startsWith("set") && a != null && a.length == 1) {
                        spawned.values.put(m.getName(), a[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
            }
        });
        return spawned;
    }

    private static ItemStack item(NinjaTool tool, int amount) {
        return new TestTaggedItem(tool.material(), Map.of(NinjaTool.KEY, tool.key()), amount);
    }

    private Launch launch(UUID thrower, NinjaTool tool) {
        Spawned projectile = spawn(Snowball.class, player(thrower).getLocation(), player(thrower));
        var event = new PlayerLaunchProjectileEvent(player(thrower), item(tool, 2), (Projectile) projectile.proxy);
        tools.onLaunchAttempt(event);
        if (!event.isCancelled()) {
            tools.onLaunch(event);
        }
        return new Launch(event, projectile);
    }

    private ProjectileHitEvent hit(Spawned projectile, Entity target) {
        var event = new ProjectileHitEvent((Projectile) projectile.proxy, target);
        tools.onHit(event);
        return event;
    }

    private PlayerInteractEvent use(UUID id, ItemStack item) {
        var event = new PlayerInteractEvent(player(id), Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF,
                EquipmentSlot.HAND);
        tools.onInteract(event);
        return event;
    }

    private void fight() {
        startTwoOnTwo();
        for (int i = 0; i < 10 && !matches.matchOf(ALICE).orElseThrow().phase().isFighting(); i++) {
            ticks(1);
        }
        assertTrue(matches.matchOf(ALICE).orElseThrow().phase().isFighting());
    }

    private void out(UUID id) {
        matches.died(player(id), null);
    }

    private static boolean glowing(Fake fake) {
        return fake.effects.stream().anyMatch(effect -> effect.getType() == PotionEffectType.GLOWING);
    }

    private AreaEffectCloudApplyEvent cloudApply(Spawned cloud, List<LivingEntity> inside) {
        var event = new AreaEffectCloudApplyEvent((AreaEffectCloud) cloud.proxy, new ArrayList<>(inside));
        tools.onCloud(event);
        return event;
    }

    @Test
    void smokeBlindsOnlyRivalsAliveInTheMatch() {
        fight();
        Launch bomb = launch(ALICE, NinjaTool.SMOKE_BOMB);
        assertFalse(bomb.event().isCancelled());
        var landed = hit(bomb.projectile(), null);
        assertTrue(landed.isCancelled());
        assertTrue(bomb.projectile().removed);
        assertEquals(1, clouds.size());
        Spawned cloud = clouds.getFirst();
        assertEquals(80, cloud.values.get("setDuration"));
        assertEquals(3.0f, cloud.values.get("setRadius"));
        assertEquals(0.0f, cloud.values.get("setRadiusPerTick"));
        assertSame(player(ALICE), cloud.values.get("setSource"));
        assertEquals(1, cloud.effects.size());
        assertEquals(PotionEffectType.BLINDNESS, cloud.effects.getFirst().getType());
        assertEquals(40, cloud.effects.getFirst().getDuration());

        out(DAVE);
        LivingEntity mob = (LivingEntity) spawn(LivingEntity.class, player(BOB).getLocation(), null).proxy;
        var applied = cloudApply(cloud, List.of(player(ALICE), player(CAROL), player(BOB), player(DAVE), player(EVE),
                mob));
        assertEquals(List.of(player(BOB)), applied.getAffectedEntities());

        // A cloud the tools did not make is left to the other rules.
        Spawned other = spawn(AreaEffectCloud.class, player(BOB).getLocation(), null);
        assertEquals(2, cloudApply(other, List.of(player(CAROL), player(BOB))).getAffectedEntities().size());
    }

    @Test
    void aBombFromAThrowerWhoIsOutBeforeItLandsMakesNoCloud() {
        fight();
        Launch bomb = launch(ALICE, NinjaTool.SMOKE_BOMB);
        out(ALICE);
        hit(bomb.projectile(), null);
        assertTrue(bomb.projectile().removed);
        assertTrue(clouds.isEmpty());
    }

    @Test
    void aShurikenStrikesThroughTheDamagePathAndMarksAHitRivalForItsGlow() {
        fight();
        Launch star = launch(ALICE, NinjaTool.SHURIKEN);
        var landed = hit(star.projectile(), player(BOB));
        assertTrue(landed.isCancelled());
        assertTrue(star.projectile().removed);
        assertEquals(List.of(new Strike(player(BOB), 2.0, (Projectile) star.projectile().proxy, player(ALICE))),
                strikes);
        assertTrue(tools.isMarked(BOB));
        PotionEffect glow = players.get(BOB).effects.getFirst();
        assertEquals(PotionEffectType.GLOWING, glow.getType());
        assertEquals(60, glow.getDuration());

        tick += 59;
        assertTrue(tools.isMarked(BOB));
        tick += 1;
        assertFalse(tools.isMarked(BOB));
        assertFalse(tools.isMarked(ALICE));
    }

    @Test
    void aShurikenNeverMarksTeammatesOrOutsiders() {
        fight();
        Launch star = launch(ALICE, NinjaTool.SHURIKEN);
        hit(star.projectile(), player(CAROL));
        assertEquals(1, strikes.size());
        assertFalse(tools.isMarked(CAROL));
        assertFalse(glowing(players.get(CAROL)));

        // Even when friendly fire lets the damage through, a teammate is not marked.
        Launch second = launch(ALICE, NinjaTool.SHURIKEN);
        tools.onShurikenDamage(new EntityDamageByEntityEvent((Entity) second.projectile().proxy, player(CAROL),
                DamageCause.PROJECTILE, (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                        new Class<?>[] {DamageSource.class}, (p, m, a) -> null), 2));
        assertFalse(tools.isMarked(CAROL));
        tools.onShurikenDamage(new EntityDamageByEntityEvent((Entity) second.projectile().proxy, player(EVE),
                DamageCause.PROJECTILE, (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                        new Class<?>[] {DamageSource.class}, (p, m, a) -> null), 2));
        assertFalse(tools.isMarked(EVE));

        // The thrower is never struck by their own shuriken.
        Launch own = launch(ALICE, NinjaTool.SHURIKEN);
        hit(own.projectile(), player(ALICE));
        assertEquals(1, strikes.size());
        assertTrue(own.projectile().removed);
    }

    @Test
    void dashSetsACappedForwardBurstAndWaitsForItsCooldown() {
        fight();
        Fake alice = players.get(ALICE);
        ItemStack dash = item(NinjaTool.DASH, 1);
        // At spawn 1, facing -X, the arena edge 10.5 blocks away shortens the burst.
        var used = use(ALICE, dash);
        assertEquals(Event.Result.DENY, used.useItemInHand());
        assertEquals(1, dash.getAmount());
        assertEquals(-0.9, alice.velocity.getX(), 1e-9);
        assertEquals(0.3, alice.velocity.getY(), 1e-9);
        assertEquals(0, alice.velocity.getZ(), 1e-9);
        assertEquals(160, alice.cooldowns.get(Material.FEATHER));

        alice.velocity = null;
        alice.location.setYaw(-90);
        tick += 159;
        use(ALICE, dash);
        assertNull(alice.velocity);
        tick += 1;
        use(ALICE, dash);
        assertEquals(new Vector(1.0, 0.3, alice.velocity.getZ()), alice.velocity);
        assertEquals(0, alice.velocity.getZ(), 1e-9);
    }

    @Test
    void dashIsRefusedWhileGlidingAndCannotLeaveTheArena() {
        fight();
        Fake alice = players.get(ALICE);
        alice.gliding = true;
        use(ALICE, item(NinjaTool.DASH, 1));
        assertNull(alice.velocity);
        assertFalse(alice.cooldowns.containsKey(Material.FEATHER));

        alice.gliding = false;
        alice.location = new Location(arenaWorld, 10.4, 64, 50.5, 90, 0);
        use(ALICE, item(NinjaTool.DASH, 1));
        assertEquals(0, alice.velocity.getX(), 1e-9);
        assertEquals(160, alice.cooldowns.get(Material.FEATHER));

        tick += 160;
        alice.velocity = null;
        alice.location = new Location(arenaWorld, 5, 64, 50.5, 90, 0);
        use(ALICE, item(NinjaTool.DASH, 1));
        assertNull(alice.velocity);
    }

    @Test
    void shelteredOutAndOutsidePlayersCannotThrowOrDashAndKeepTheItem() {
        startTwoOnTwo();
        assertEquals(MatchPhase.PREP, matches.matchOf(ALICE).orElseThrow().phase());
        for (NinjaTool tool : List.of(NinjaTool.SMOKE_BOMB, NinjaTool.SHURIKEN)) {
            assertTrue(launch(ALICE, tool).event().isCancelled());
            assertEquals(Event.Result.DENY, use(ALICE, item(tool, 2)).useItemInHand());
        }
        assertEquals(Event.Result.DENY, use(ALICE, item(NinjaTool.DASH, 1)).useItemInHand());
        assertNull(players.get(ALICE).velocity);

        for (int i = 0; i < 10 && !matches.matchOf(ALICE).orElseThrow().phase().isFighting(); i++) {
            ticks(1);
        }
        out(ALICE);
        assertTrue(matches.isDisarmed(ALICE));
        assertTrue(launch(ALICE, NinjaTool.SHURIKEN).event().isCancelled());
        use(ALICE, item(NinjaTool.DASH, 1));
        assertNull(players.get(ALICE).velocity);

        assertTrue(launch(EVE, NinjaTool.SMOKE_BOMB).event().isCancelled());
        assertEquals(Event.Result.DENY, use(EVE, item(NinjaTool.DASH, 1)).useItemInHand());
        assertNull(players.get(EVE).velocity);

        // Ordinary snowballs and items are not the tools' business.
        Spawned snowball = spawn(Snowball.class, player(EVE).getLocation(), player(EVE));
        var plain = new PlayerLaunchProjectileEvent(player(EVE), new TestTaggedItem(Material.SNOWBALL, Map.of(), 1),
                (Projectile) snowball.proxy);
        tools.onLaunchAttempt(plain);
        assertFalse(plain.isCancelled());
        assertFalse(hit(snowball, player(BOB)).isCancelled());
        assertFalse(snowball.removed);
    }

    @Test
    void roundEndDiscardsFlyingToolsCloudsCooldownsAndMarks() {
        fight();
        Launch flying = launch(ALICE, NinjaTool.SMOKE_BOMB);
        hit(launch(ALICE, NinjaTool.SMOKE_BOMB).projectile(), null);
        hit(launch(CAROL, NinjaTool.SHURIKEN).projectile(), player(BOB));
        use(ALICE, item(NinjaTool.DASH, 1));
        assertTrue(tools.isMarked(BOB));
        assertEquals(160, players.get(ALICE).cooldowns.get(Material.FEATHER));

        out(BOB);
        assertFalse(tools.isMarked(BOB));
        assertFalse(glowing(players.get(BOB)));
        out(DAVE);
        runNextTick();
        assertEquals(1, events(RoundEndEvent.class).size());
        assertTrue(flying.projectile().removed);
        assertTrue(clouds.getFirst().removed);
        assertEquals(0, players.get(ALICE).cooldowns.get(Material.FEATHER));
        assertFalse(hit(flying.projectile(), player(BOB)).isCancelled());
        assertEquals(1, clouds.size());

        // The next round starts with a ready dash.
        for (int i = 0; i < 10 && !matches.matchOf(ALICE).orElseThrow().phase().isFighting(); i++) {
            ticks(1);
        }
        assertEquals(2, matches.matchOf(ALICE).orElseThrow().round());
        players.get(ALICE).velocity = null;
        use(ALICE, item(NinjaTool.DASH, 1));
        assertTrue(players.get(ALICE).velocity != null);
    }

    @Test
    void leavingDiscardsThatPlayersToolsCooldownAndMark() {
        fight();
        hit(launch(BOB, NinjaTool.SHURIKEN).projectile(), player(ALICE));
        assertTrue(tools.isMarked(ALICE));
        Launch flying = launch(ALICE, NinjaTool.SHURIKEN);
        Launch other = launch(BOB, NinjaTool.SMOKE_BOMB);
        use(ALICE, item(NinjaTool.DASH, 1));

        matches.leave(player(ALICE), MatchService.Exit.COMMAND);
        assertTrue(flying.projectile().removed);
        assertFalse(other.projectile().removed);
        assertFalse(tools.isMarked(ALICE));
        assertFalse(glowing(players.get(ALICE)));
        assertEquals(0, players.get(ALICE).cooldowns.get(Material.FEATHER));
    }

    @Test
    void eliminationDiscardsThatPlayersCloudsAndFlyingTools() {
        fight();
        hit(launch(ALICE, NinjaTool.SMOKE_BOMB).projectile(), null);
        Launch flying = launch(ALICE, NinjaTool.SHURIKEN);
        hit(launch(BOB, NinjaTool.SMOKE_BOMB).projectile(), null);
        assertEquals(2, clouds.size());

        out(ALICE);
        assertTrue(clouds.get(0).removed);
        assertTrue(flying.projectile().removed);
        assertFalse(clouds.get(1).removed);
        assertEquals(1, cloudApply(clouds.get(1), List.of(player(CAROL))).getAffectedEntities().size());
    }

    @Test
    void matchEndAndCloseDiscardEverything() {
        fight();
        Launch flying = launch(ALICE, NinjaTool.SHURIKEN);
        hit(launch(BOB, NinjaTool.SMOKE_BOMB).projectile(), null);
        matches.close();
        assertEquals(1, events(MatchEndEvent.class).size());
        assertTrue(flying.projectile().removed);
        assertTrue(clouds.getFirst().removed);

        // Closing the tools themselves, as on plugin disable, needs no match event.
        tools = new NinjaTools(matches, plugin.getServer()::getPlayer, name -> arenas.get(name).map(Arena::bounds),
                ToolSettings.DEFAULTS, () -> tick, this::strike, at -> {
                    Spawned cloud = spawn(AreaEffectCloud.class, at, null);
                    clouds.add(cloud);
                    return (AreaEffectCloud) cloud.proxy;
                });
        fight();
        Launch pending = launch(ALICE, NinjaTool.SMOKE_BOMB);
        hit(launch(BOB, NinjaTool.SMOKE_BOMB).projectile(), null);
        hit(launch(BOB, NinjaTool.SHURIKEN).projectile(), player(ALICE));
        use(ALICE, item(NinjaTool.DASH, 1));
        tools.close();
        assertTrue(pending.projectile().removed);
        assertTrue(clouds.getLast().removed);
        assertFalse(tools.isMarked(ALICE));
        assertFalse(glowing(players.get(ALICE)));
        assertEquals(0, players.get(ALICE).cooldowns.get(Material.FEATHER));
    }

    @Test
    void lockdownKeepsToolsInTheArenaAndLeavingTakesThemAway() {
        fight();
        Fake alice = players.get(ALICE);
        alice.storage = new ItemStack[36];
        for (NinjaTool tool : NinjaTool.values()) {
            alice.storage[1 + tool.ordinal()] = item(tool, 1);
        }
        Item dropped = (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[] {Item.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getItemStack" -> item(NinjaTool.SHURIKEN, 1);
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        var drop = new PlayerDropItemEvent(player(ALICE), dropped);
        new InventoryLockdownListener(matches::isInMatch).onDrop(drop);
        assertTrue(drop.isCancelled());

        matches.leave(player(ALICE), MatchService.Exit.COMMAND);
        assertTrue(Arrays.stream(alice.storage).allMatch(Objects::isNull));
    }
}
