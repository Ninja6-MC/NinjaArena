package com.ninja6.ninjaarena.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.lobby.LobbyLayout;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

class ShopBoardTest {
    private final List<FakeEntity> spawned = new ArrayList<>();
    private final List<Player> opened = new ArrayList<>();
    private final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[] {World.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getEntities" -> spawned.stream().filter(e -> !e.removed).map(e -> e.entity).toList();
                case "spawn" -> spawn((Location) args[0], (Class<?>) args[1], args[2]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    private final ShopBoard board = new ShopBoard(world, LobbyLayout.bundled(), opened::add);

    @SuppressWarnings("unchecked")
    private Entity spawn(Location at, Class<?> type, Object initializer) {
        FakeEntity fake = new FakeEntity(at, type);
        spawned.add(fake);
        ((Consumer<Entity>) initializer).accept(fake.entity);
        return fake.entity;
    }
    private final class FakeEntity {
        final Location at;
        final Class<?> type;
        final Map<String, Object> properties = new HashMap<>();
        final Map<Object, Object> data = new HashMap<>();
        boolean removed;
        final PersistentDataContainer pdc = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[] {PersistentDataContainer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> { data.put(args[0], args[2]); yield null; }
                    case "has" -> data.containsKey(args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final Entity entity;
        FakeEntity(Location at, Class<?> type) {
            this.at = at;
            this.type = type;
            entity = (Entity) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("set") || method.getName().equals("text")) {
                            properties.put(method.getName(), args[0]);
                            return null;
                        }
                        return switch (method.getName()) {
                            case "getWorld" -> world;
                            case "getPersistentDataContainer" -> pdc;
                            case "remove" -> { removed = true; yield null; }
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }
    }
    private Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
    }

    @Test void boardUsesTextAndInteractionAndRepeatedPlacementRemovesOnlyOwnedEntities() {
        FakeEntity decoration = new FakeEntity(board.location(), TextDisplay.class);
        spawned.add(decoration);
        board.place();
        assertFalse(decoration.removed);
        assertEquals(TextDisplay.class, spawned.get(1).type);
        assertEquals(Interaction.class, spawned.get(2).type);
        assertTrue(spawned.get(1).properties.get("text") instanceof Component);
        assertEquals(false, spawned.get(1).properties.get("setPersistent"));
        assertEquals(true, spawned.get(1).properties.get("setInvulnerable"));
        assertEquals(3f, spawned.get(2).properties.get("setInteractionWidth"));
        assertEquals(2f, spawned.get(2).properties.get("setInteractionHeight"));
        assertEquals(board.location().getY() - 1, spawned.get(2).at.getY());
        board.place(); // Restart reconciliation or lobby reset.
        assertTrue(spawned.get(1).removed);
        assertTrue(spawned.get(2).removed);
        assertFalse(decoration.removed);
        assertEquals(3, world.getEntities().size());
        board.close();
        assertEquals(1, world.getEntities().size());
    }

    @Test void useAndAttackOpenTheSameScreenAndCancelEvenAnEditorsAttack() {
        board.place();
        Player player = player();
        Entity target = spawned.get(1).entity;
        var use = new PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND);
        board.onUse(use);
        assertTrue(use.isCancelled());
        var offhand = new PlayerInteractEntityEvent(player, target, EquipmentSlot.OFF_HAND);
        board.onUse(offhand);
        assertTrue(offhand.isCancelled());
        assertEquals(1, opened.size());
        var attack = new PrePlayerAttackEntityEvent(player, target, true);
        board.onAttack(attack);
        assertTrue(attack.isCancelled());
        assertEquals(2, opened.size());
        FakeEntity unrelated = new FakeEntity(board.location(), Interaction.class);
        board.onUse(new PlayerInteractEntityEvent(player, unrelated.entity, EquipmentSlot.HAND));
        assertEquals(2, opened.size());
    }
}
