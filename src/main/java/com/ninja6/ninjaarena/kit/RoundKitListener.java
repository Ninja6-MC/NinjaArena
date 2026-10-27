package com.ninja6.ninjaarena.kit;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

/** Rebuilds the whole kit each round, including a delayed respawn into a new round. */
public final class RoundKitListener implements Listener {

    private final MatchService matches;
    private final Function<UUID, Player> players;
    private final KitDefinition definition;
    private final KitItems items;
    private final Logger logger;

    public RoundKitListener(MatchService matches, Function<UUID, Player> players, KitDefinition definition,
            KitItems items, Logger logger) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.players = Objects.requireNonNull(players, "players");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.items = Objects.requireNonNull(items, "items");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRoundStart(RoundStartEvent event) {
        for (UUID id : event.match().players()) {
            Player player = players.apply(id);
            if (player != null) {
                apply(player);
            }
        }
    }

    /** Runs after death handling's HIGH handler has placed and readied the player. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        apply(event.getPlayer());
    }

    private void apply(Player player) {
        UUID id = player.getUniqueId();
        if (player.isDead() || !player.isOnline() || player.getGameMode() == GameMode.SPECTATOR
                || matches.matchOf(id).filter(match -> match.isAlive(id)).isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> kit = new LinkedHashMap<>();
        try {
            for (var entry : definition.items()) {
                kit.put(entry.slot(), items.create(entry, player));
            }
        } catch (RuntimeException e) {
            logger.warning("Could not apply kits.yml for " + player.getName() + ": " + e.getMessage()
                    + "; using an empty round kit.");
            kit.clear();
        }
        // Clear crafting and cursor before closing, so closing cannot return or drop old kit items.
        var top = player.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING && top.getHolder() instanceof Player owner
                && owner.getUniqueId().equals(id)) {
            top.clear();
        }
        player.setItemOnCursor(null);
        player.closeInventory();
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);
        inventory.setHeldItemSlot(0);
        kit.forEach(inventory::setItem);
    }
}
