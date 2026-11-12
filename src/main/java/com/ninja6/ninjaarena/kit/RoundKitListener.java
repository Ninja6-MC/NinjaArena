package com.ninja6.ninjaarena.kit;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.PlayerRejoinMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.tool.ToolSettings;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/** Rebuilds the whole kit each round, including a delayed respawn into a new round. */
public final class RoundKitListener implements Listener {

    private final MatchService matches;
    private final Function<UUID, Player> players;
    private KitDefinition definition;
    private final Map<MatchView, KitDefinition> roundKits = new IdentityHashMap<>();
    private final KitItems items;
    private final Logger logger;
    private BiConsumer<MatchView, Player> afterKit = (match, player) -> { };

    /** Runs after every successful round kit rebuild, including delayed respawns. */
    public void setAfterKit(BiConsumer<MatchView, Player> hook) {
        afterKit = Objects.requireNonNull(hook, "hook");
    }

    public RoundKitListener(MatchService matches, Function<UUID, Player> players, KitDefinition definition,
            KitItems items, Logger logger) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.players = Objects.requireNonNull(players, "players");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.items = Objects.requireNonNull(items, "items");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Checks the loaded kit once, never when handing items to players. */
    public void warnToolAmounts(ToolSettings settings) {
        definition.warnToolAmounts(settings, logger);
    }

    /** The current round, including its delayed respawns, keeps its kit definition. */
    public void setDefinition(KitDefinition definition) {
        for (MatchView match : matches.matches()) {
            if (match.result().isEmpty()) {
                roundKits.putIfAbsent(match, this.definition);
            }
        }
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchEnd(MatchEndEvent event) {
        roundKits.remove(event.match());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRoundStart(RoundStartEvent event) {
        roundKits.put(event.match(), definition);
        for (UUID id : event.match().players()) {
            Player player = players.apply(id);
            if (player != null) {
                apply(player);
            }
        }
    }

    /** A player back in their match during PREP is alive in the round and gets its kit. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRejoin(PlayerRejoinMatchEvent event) {
        Player player = players.apply(event.player());
        if (player != null) {
            apply(player);
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
            KitDefinition roundKit = roundKits.computeIfAbsent(matches.matchOf(id).orElseThrow(), match -> definition);
            for (var entry : roundKit.items()) {
                kit.put(entry.slot(), items.create(entry, player));
            }
        } catch (RuntimeException e) {
            logger.warning("Could not apply kits.yml for " + player.getName() + ": " + e.getMessage()
                    + "; using an empty round kit.");
            kit.clear();
        }
        KitInventory.clear(player);
        var inventory = player.getInventory();
        inventory.setHeldItemSlot(0);
        kit.forEach(inventory::setItem);
        afterKit.accept(matches.matchOf(id).orElseThrow(), player);
    }
}
