package com.ninja6.ninjaarena.shop;

import com.ninja6.ninjaarena.sound.Sounds;
import com.ninja6.ninjaarena.sound.Sounds.Cue;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.PlayerPoints;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.tool.NinjaTools;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Personal round purchases. All methods run on the server thread. Buying is open only while
 * the injected predicate allows it: on a server, while the buyer's match is in SHOP.
 */
public final class Shop implements Listener {
    public enum Result {
        BOUGHT, NOT_ENOUGH_POINTS, LIMIT_REACHED, UNKNOWN_ITEM, NO_PERMISSION, CLOSED;

        public String messageKey() {
            return "shop." + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    private static final class Purchases {
        private final Map<String, Integer> counts = new HashMap<>();
        private final List<ShopCatalogue.Entry> pending = new ArrayList<>();
        private final Set<PotionEffectType> potions = new HashSet<>();
    }

    private final MatchService matches;
    private final PlayerPoints points;
    private final KitItems items;
    private final NinjaTools tools;
    private final Function<UUID, Player> players;
    private final Logger logger;
    private final Predicate<MatchView> open;
    private Sounds sounds;

    private ShopCatalogue definition;
    private final Map<MatchView, ShopCatalogue> catalogues = new IdentityHashMap<>();
    private final Map<MatchView, Map<UUID, Purchases>> purchases = new IdentityHashMap<>();

    public Shop(MatchService matches, PlayerPoints points, KitItems items, NinjaTools tools,
            Function<UUID, Player> players, Logger logger, ShopCatalogue definition) {
        this(matches, points, items, tools, players, logger, definition, match -> false);
    }

    public Shop(MatchService matches, PlayerPoints points, KitItems items, NinjaTools tools,
            Function<UUID, Player> players, Logger logger, ShopCatalogue definition, Predicate<MatchView> open) {
        this.matches = matches;
        this.points = points;
        this.items = items;
        this.tools = tools;
        this.players = players;
        this.logger = logger;
        this.definition = definition;
        this.open = open;
    }

    public void setSounds(Sounds sounds) {
        this.sounds = sounds;
    }

    public ShopCatalogue catalogue(MatchView match) {
        return catalogues.computeIfAbsent(match, ignored -> definition);
    }

    public void setDefinition(ShopCatalogue next) {
        matches.matches().stream().filter(match -> match.result().isEmpty()).forEach(this::catalogue);
        definition = next;
    }

    public Result buy(MatchView match, Player player, String itemId) {
        UUID id = player.getUniqueId();
        if (!match.contains(id) || matches.matchOf(id).filter(current -> current == match).isEmpty()
                || match.result().isPresent() || !open.test(match)) {
            return Result.CLOSED;
        }
        if (!player.hasPermission("ninjaarena.shop.use")) {
            return Result.NO_PERMISSION;
        }
        ShopCatalogue.Entry entry = itemId == null ? null : catalogue(match).items().get(itemId);
        if (entry == null) {
            return Result.UNKNOWN_ITEM;
        }
        Purchases cart = purchases.computeIfAbsent(match, ignored -> new HashMap<>())
                .computeIfAbsent(id, ignored -> new Purchases());
        if (cart.counts.getOrDefault(itemId, 0) >= entry.limit()) {
            return Result.LIMIT_REACHED;
        }
        if (!points.spend(match, id, entry.price())) {
            return Result.NOT_ENOUGH_POINTS;
        }
        cart.counts.merge(itemId, 1, Integer::sum);
        cart.pending.add(entry);
        if (sounds != null) {
            sounds.player(Cue.SHOP_BUY, player);
        }
        return Result.BOUGHT;
    }

    /** Installed directly at the end of RoundKitListener.apply, never ordered as a separate listener. */
    public void apply(MatchView match, Player player) {
        tools.prepareRound(player.getUniqueId());
        Purchases cart = purchases.getOrDefault(match, Map.of()).get(player.getUniqueId());
        if (cart == null) {
            return;
        }
        for (ShopCatalogue.Entry entry : cart.pending) {
            try {
                give(player, entry.gift(), cart);
            } catch (RuntimeException e) {
                points.refund(match, player.getUniqueId(), entry.price());
                logger.warning("Could not apply shop item " + entry.id() + ": " + e.getMessage() + "; refunded.");
            }
        }
        cart.pending.clear();
    }

    private void give(Player player, ShopCatalogue.Gift gift, Purchases cart) {
        switch (gift) {
            case ShopCatalogue.Armour armour -> {
                String prefix = armour.tier().equals("chain") ? "CHAINMAIL"
                        : armour.tier().toUpperCase(java.util.Locale.ROOT);
                player.getInventory().setArmorContents(new ItemStack[] {
                    items.create(new KitDefinition.Entry(0,
                            Material.valueOf(prefix + "_BOOTS"), null, 1), player),
                    items.create(new KitDefinition.Entry(0,
                            Material.valueOf(prefix + "_LEGGINGS"), null, 1), player),
                    items.create(new KitDefinition.Entry(0,
                            Material.valueOf(prefix + "_CHESTPLATE"), null, 1), player),
                    items.create(new KitDefinition.Entry(0,
                            Material.valueOf(prefix + "_HELMET"), null, 1), player)});
            }
            case ShopCatalogue.ToolUses bonus -> tools.addRoundUses(player.getUniqueId(), bonus.tool(), bonus.count());
            case ShopCatalogue.Potion potion -> {
                player.addPotionEffect(new PotionEffect(potion.type(), potion.duration(), potion.amplifier()));
                cart.potions.add(potion.type());
            }
            case ShopCatalogue.Consumable consumable -> {
                ItemStack item = items.create(consumable.item(), player);
                // Reserve a storage slot first: a full kit cannot receive this purchase, so refund it.
                int slot = player.getInventory().firstEmpty();
                if (slot < 0 || slot > 35) {
                    throw new IllegalStateException("inventory is full");
                }
                player.getInventory().setItem(slot, item);
            }
        }
    }

    /** Anything the kit hook could not deliver at round start is cancelled, including death-screen players. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundStart(RoundStartEvent event) {
        catalogue(event.match());
        purchases.getOrDefault(event.match(), Map.of()).forEach((id, cart) -> refund(event.match(), id, cart));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(RoundEndEvent event) {
        clear(event.match(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeave(PlayerLeaveMatchEvent event) {
        Purchases cart = purchases.getOrDefault(event.match(), Map.of()).get(event.player());
        if (cart != null) {
            refund(event.match(), event.player(), cart);
            removePotions(event.player(), cart);
            purchases.get(event.match()).remove(event.player());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchEnd(MatchEndEvent event) {
        clear(event.match(), false);
        catalogues.remove(event.match());
    }

    private void clear(MatchView match, boolean refund) {
        Map<UUID, Purchases> carts = purchases.remove(match);
        if (carts != null) {
            carts.forEach((id, cart) -> {
                if (refund) {
                    refund(match, id, cart);
                }
                removePotions(id, cart);
            });
        }
    }

    private void refund(MatchView match, UUID id, Purchases cart) {
        cart.pending.forEach(entry -> points.refund(match, id, entry.price()));
        cart.pending.clear();
    }

    private void removePotions(UUID id, Purchases cart) {
        Player player = players.apply(id);
        if (player != null) {
            cart.potions.forEach(player::removePotionEffect);
        }
        cart.potions.clear();
    }
}
