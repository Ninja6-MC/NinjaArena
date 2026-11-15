package com.ninja6.ninjaarena.shop;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.lobby.LobbyLayout;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/** A text display and click target owned by the plugin, including during lobby editing. */
public final class ShopBoard implements Listener, AutoCloseable {
    private static final NamespacedKey KEY = new NamespacedKey("ninjaarena", "shop_board");
    private final World world;
    private final Location at;
    private final Consumer<Player> open;

    public ShopBoard(World world, LobbyLayout layout, Consumer<Player> open) {
        this.world = world;
        var point = layout.boards().get("shop");
        this.at = new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        this.open = open;
    }

    public Location location() { return at.clone(); }

    /** Call after the board's chunk is loaded, on enable and after a lobby reset. */
    public void place() {
        close();
        world.spawn(at, TextDisplay.class, display -> {
            mark(display);
            display.text(msg("shop.board"));
            display.setBillboard(Display.Billboard.FIXED);
            display.setLineWidth(240);
        });
        world.spawn(at.clone().subtract(0, 1, 0), Interaction.class, target -> {
            mark(target);
            target.setInteractionWidth(3);
            target.setInteractionHeight(2);
            target.setResponsive(true);
        });
    }

    private static void mark(Entity entity) {
        entity.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        entity.setPersistent(false);
        entity.setInvulnerable(true);
        entity.setGravity(false);
    }

    private boolean owns(Entity entity) {
        return entity.getWorld() == world && entity.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEntityEvent event) {
        // The at-entity event precedes the ordinary use packet; handle just the latter.
        if (!(event instanceof PlayerInteractAtEntityEvent) && owns(event.getRightClicked())) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND) { open.accept(event.getPlayer()); }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (owns(event.getAttacked())) {
            event.setCancelled(true);
            open.accept(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (owns(event.getEntity())) { event.setCancelled(true); }
    }

    @Override public void close() {
        world.getEntities().stream().filter(this::owns).forEach(Entity::remove);
    }
}
