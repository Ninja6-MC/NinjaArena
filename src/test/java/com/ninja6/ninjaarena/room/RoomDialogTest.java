package com.ninja6.ninjaarena.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class RoomDialogTest {
    private static final UUID ID = new UUID(0, 1);
    private final List<Runnable> pending = new ArrayList<>();
    private final Map<UUID, Player> online = new HashMap<>();
    private final List<UUID> lookedUp = new ArrayList<>();
    private final List<Player> pressed = new ArrayList<>();
    private final RoomDialog screens = new RoomDialog(pending::add, id -> {
        lookedUp.add(id);
        return online.get(id);
    });

    private final int index = 2;
    private final Map<String, String> values = Map.of("value", "hard");

    private Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) { return ID; }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private void press(Player audience) {
        screens.relay(audience, index, values, (player, option, submitted) -> {
            assertEquals(index, option);
            assertEquals(values, submitted);
            pressed.add(player);
        });
    }

    @Test void callbackLooksUpTheCurrentPlayerOnlyWhenTheServerTaskRuns() {
        Player audience = player();
        Player current = player();
        online.put(ID, audience);
        press(audience);
        assertTrue(lookedUp.isEmpty());
        assertTrue(pressed.isEmpty());
        assertEquals(1, pending.size());
        online.put(ID, current);
        pending.removeFirst().run();
        assertEquals(List.of(ID), lookedUp);
        assertEquals(1, pressed.size());
        assertSame(current, pressed.getFirst());
    }

    @Test void callbackDoesNothingIfThePlayerDisconnectsBeforeTheServerTaskRuns() {
        Player audience = player();
        online.put(ID, audience);
        press(audience);
        online.clear();
        pending.removeFirst().run();
        assertEquals(List.of(ID), lookedUp);
        assertTrue(pressed.isEmpty());
    }

    @Test void callbacksUseAnExplicitShortLifetimeWithUnlimitedUses() {
        assertEquals(Duration.ofMinutes(30), RoomDialog.CALLBACK_OPTIONS.lifetime());
        assertEquals(ClickCallback.UNLIMITED_USES, RoomDialog.CALLBACK_OPTIONS.uses());
    }
}
