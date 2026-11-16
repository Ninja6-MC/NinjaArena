package com.ninja6.ninjaarena.dialog;

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

class DialogRelayTest {
    private static final UUID ID = new UUID(0, 1);
    private final List<Runnable> pending = new ArrayList<>();
    private final Map<UUID, Player> online = new HashMap<>();
    private final List<UUID> lookedUp = new ArrayList<>();
    private final List<Player> pressed = new ArrayList<>();

    private Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) { return ID; }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private void press(Player audience) {
        DialogRelay.onMain(audience, pending::add, id -> {
            lookedUp.add(id);
            return online.get(id);
        }, pressed::add);
    }

    @Test void looksUpTheCurrentPlayerOnlyWhenTheServerTaskRuns() {
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

    @Test void doesNothingIfThePlayerDisconnectsBeforeTheServerTaskRuns() {
        Player audience = player();
        online.put(ID, audience);
        press(audience);
        online.clear();
        pending.removeFirst().run();
        assertEquals(List.of(ID), lookedUp);
        assertTrue(pressed.isEmpty());
    }
}
