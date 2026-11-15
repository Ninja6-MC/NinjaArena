package com.ninja6.ninjaarena.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ShopDialogTest {
    private static final UUID ID = new UUID(0, 1);

    private Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) { return ID; }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test void callbackLooksUpThePlayerOnTheServerThreadInsteadOfRetainingTheAudience() {
        List<Runnable> pending = new ArrayList<>();
        Map<UUID, Player> online = new HashMap<>();
        List<Player> pressed = new ArrayList<>();
        var dialog = new ShopDialog(pending::add, online::get);
        Player audience = player();
        Player current = player();
        online.put(ID, audience);
        var action = new ShopMenus.Action(ShopMenus.Kind.READY);
        dialog.relay(audience, action, (player, button) -> {
            assertEquals(action, button);
            pressed.add(player);
        });
        assertTrue(pressed.isEmpty());
        online.put(ID, current);
        pending.removeFirst().run();
        assertEquals(1, pressed.size());
        assertSame(current, pressed.getFirst());
    }

    @Test void callbackDoesNothingIfThePlayerDisconnectsBeforeTheServerTaskRuns() {
        List<Runnable> pending = new ArrayList<>();
        Map<UUID, Player> online = new HashMap<>();
        List<Player> pressed = new ArrayList<>();
        var dialog = new ShopDialog(pending::add, online::get);
        Player audience = player();
        online.put(ID, audience);
        dialog.relay(audience, new ShopMenus.Action(ShopMenus.Kind.READY), (player, action) -> pressed.add(player));
        online.clear();
        pending.removeFirst().run();
        assertTrue(pressed.isEmpty());
    }
}
