package com.ninja6.ninjaarena.match;

import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType.Category;
import org.bukkit.potion.PotionType;

/** Player rules use the match engine's public lookup and round events. */
public final class MatchProtectionListener implements Listener {

    private final MatchService matches;
    private final Function<UUID, Player> players;
    private final boolean friendlyFire;
    private final Predicate<UUID> spectators;

    public MatchProtectionListener(MatchService matches, Function<UUID, Player> players, boolean friendlyFire,
            Predicate<UUID> spectators) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.players = Objects.requireNonNull(players, "players");
        this.friendlyFire = friendlyFire;
        this.spectators = Objects.requireNonNull(spectators, "spectators");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = source(event.getDamager());
        if (attacker == null) {
            attacker = source(event.getDamageSource().getCausingEntity());
        }
        if (teammate(attacker, victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player attacker)
                || !harmful(event.getPotion().getEffects())) {
            return;
        }
        // Set only the teammate's intensity to zero; enemies and the thrower keep theirs.
        for (var affected : List.copyOf(event.getAffectedEntities())) {
            if (affected instanceof Player victim && teammate(attacker, victim)) {
                event.setIntensity(victim, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        PotionType base = cloud.getBasePotionType();
        if (!(cloud.getSource() instanceof Player attacker)
                || !(harmful(cloud.getCustomEffects()) || base != null && harmful(base.getPotionEffects()))) {
            return;
        }
        event.getAffectedEntities().removeIf(affected -> affected instanceof Player victim
                && teammate(attacker, victim));
    }

    @EventHandler
    public void onRoundStart(RoundStartEvent event) {
        for (UUID id : event.match().players()) {
            Player player = players.apply(id);
            if (player != null && !player.isDead() && matches.isInMatch(id)) {
                player.setGameMode(GameMode.ADVENTURE);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        GameMode allowed = spectators.test(id) ? GameMode.SPECTATOR : GameMode.ADVENTURE;
        if (matches.isInMatch(id) && event.getNewGameMode() != allowed) {
            event.setCancelled(true);
        }
    }

    private boolean teammate(Player attacker, Player victim) {
        if (friendlyFire || attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return false;
        }
        return matches.matchOf(attacker.getUniqueId())
                .filter(match -> match.contains(victim.getUniqueId())
                        && match.teamOf(attacker.getUniqueId()) == match.teamOf(victim.getUniqueId()))
                .isPresent();
    }

    private static Player source(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) {
            return player;
        }
        return null;
    }

    private static boolean harmful(Collection<PotionEffect> effects) {
        return effects.stream().anyMatch(effect -> effect.getType().getEffectCategory() == Category.HARMFUL);
    }
}
