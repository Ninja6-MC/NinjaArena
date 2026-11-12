package com.ninja6.ninjaarena.reward;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.NinjaArenaPlugin;
import com.ninja6.ninjaarena.match.MatchResult;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.stats.MatchRecord;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread bridge between the stats executor and Bukkit inventory/economy operations. */
public final class Rewards implements Listener {

    private final NinjaArenaPlugin plugin;
    private final Set<UUID> checking = new HashSet<>();
    private final BukkitTask refresh;
    private final NamespacedKey trophyKey;
    private final Map<UUID, Runnable> undelivered = new HashMap<>();
    private final Predicate<UUID> busy;
    private boolean closed;
    private RewardSettings settings;
    private boolean placing;

    /** @param busy whether a player is in a match, a waiting room, the hub or the lobby, or spectating */
    public Rewards(NinjaArenaPlugin plugin, RewardSettings settings, Predicate<UUID> busy) {
        this.plugin = plugin;
        this.settings = settings;
        this.busy = Objects.requireNonNull(busy, "busy");
        trophyKey = new NamespacedKey(plugin, "season_trophy");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        refresh = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 1, 600);
    }

    public void settings(RewardSettings settings) {
        this.settings = settings;
    }

    public synchronized void close() {
        refresh.cancel();
        closed = true;
        undelivered.values().forEach(Runnable::run);
        undelivered.clear();
    }

    private void refresh() {
        if (!placing && !settings.placings().isEmpty()) {
            placing = true;
            plugin.stats().awardPlacings(settings.placings()).whenComplete((ignored, error) ->
                    sync(() -> placing = false));
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            check(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTrophyUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item != null && item.getPersistentDataContainer().has(trophyKey)) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        check(event.getPlayer().getUniqueId());
    }

    public void onRecorded(MatchRecord record) {
        if (!settings.milestones().isEmpty()) {
            record.players().forEach(player -> plugin.stats().evaluateMilestones(player.player(), settings.milestones()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchEnd(MatchEndEvent event) {
        MatchResult result = event.result();
        if (result.reason() != MatchResult.Reason.COMPLETED && result.reason() != MatchResult.Reason.FORFEIT) {
            return;
        }
        var bonus = event.match().eventBonus();
        RewardSettings.Payout base = settings.payout();
        RewardSettings.Payout payout = new RewardSettings.Payout(base.enabled(), base.amount() + bonus.cents(),
                base.dailyCap());
        boolean pay = payout.enabled() && VaultPayout.available(plugin.getServer());
        long day = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay();
        for (UUID id : event.match().players()) {
            if (bonus.cosmetic() != null) {
                plugin.stats().grantEventReward(id, bonus.cosmetic(), bonus.seasonal());
            }
            Player player = plugin.getServer().getPlayer(id);
            if (pay && player != null && player.hasPermission("ninjaarena.rewards.payout")) {
                plugin.stats().reservePayout(id, result.id(), day, payout).thenAccept(amount -> {
                    if (amount > 0) {
                        sync(() -> {
                            if (!VaultPayout.deposit(plugin.getServer(), plugin.getServer().getOfflinePlayer(id), amount)) {
                                plugin.getLogger().warning("Vault payout failed for " + id + " in match " + result.id());
                            }
                        });
                    }
                });
            }
        }
    }

    private void check(UUID id) {
        if (!checking.add(id)) {
            return;
        }
        plugin.stats().evaluateMilestones(id, settings.milestones()).whenComplete((awards, error) -> sync(() -> {
            checking.remove(id);
            if (error == null && canDeliver(id)) {
                awards.stream().filter(Award::pending).findFirst().ifPresent(award -> deliver(id, award));
            }
        }));
    }

    private boolean canDeliver(UUID id) {
        return deliverable(plugin.getServer().getPlayer(id), busy, plugin.restores()::shouldRestoreOnJoin);
    }

    /**
     * Whether a trophy can be handed to a player now: they are online and alive, outside both
     * plugin worlds, not in a match, a waiting room, the hub or the lobby, or spectating, have
     * nothing waiting to be restored, and have a free slot. A claim is checked again just
     * before the item is placed, so a player who has since entered a plugin world keeps it
     * pending.
     */
    static boolean deliverable(Player player, Predicate<UUID> busy, Predicate<UUID> restorePending) {
        return player != null && !player.isDead() && !PluginWorlds.isPluginWorld(player.getWorld())
                && !busy.test(player.getUniqueId()) && !restorePending.test(player.getUniqueId())
                && player.getInventory().firstEmpty() >= 0;
    }

    private void deliver(UUID id, Award award) {
        if (!checking.add(id)) {
            return;
        }
        var store = plugin.stats();
        UUID claim = UUID.randomUUID();
        Runnable release = () -> store.trophyClaim(id, award, claim, true);
        synchronized (this) {
            undelivered.put(claim, release);
        }
        store.trophyClaim(id, award, claim, false).whenComplete((claimed, error) -> {
            if (error == null && !claimed) {
                forgetClaim(claim);
                sync(() -> checking.remove(id));
                return;
            }
            dispatchTrophy(claim, release, () -> {
                checking.remove(id);
                if (error != null || !canDeliver(id)) {
                    restorePending(id, award, claim);
                    return;
                }
                Player player = plugin.getServer().getPlayer(id);
                ItemStack trophy = ItemStack.of(Material.KNOWLEDGE_BOOK);
                trophy.editMeta(meta -> {
                    meta.displayName(Component.text(award.trophy()));
                    meta.lore(List.of(msg("reward.trophy-lore", "season", award.season())));
                    meta.getPersistentDataContainer().set(trophyKey, PersistentDataType.BYTE, (byte) 1);
                });
                player.getInventory().setItem(player.getInventory().firstEmpty(), trophy);
                forgetClaim(claim);
            });
        });
    }

    private synchronized void dispatchTrophy(UUID claim, Runnable release, Runnable delivery) {
        if (closed) {
            return;
        }
        if (!sync(() -> {
            synchronized (this) {
                if (!undelivered.containsKey(claim)) {
                    return;
                }
            }
            delivery.run();
        })) {
            undelivered.remove(claim);
            release.run();
        }
    }

    private void restorePending(UUID id, Award award, UUID claim) {
        plugin.stats().trophyClaim(id, award, claim, true).whenComplete((ignored, error) -> {
            if (error == null) {
                forgetClaim(claim);
            } else {
                sync(() -> plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> restorePending(id, award, claim), 100));
            }
        });
    }

    private synchronized void forgetClaim(UUID claim) {
        undelivered.remove(claim);
    }

    private boolean sync(Runnable task) {
        if (!plugin.isEnabled()) {
            return false;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
            return true;
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            return false;
        }
    }
}
