package com.ninja6.ninjaarena.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class ArenaWorldTest {

    private final List<String> calls = new ArrayList<>();

    /** A world that records the saving calls made to it. */
    private World recordingWorld() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setAutoSave" -> calls.add("setAutoSave(" + args[0] + ")");
                        case "save" -> calls.add("save");
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                    return null;
                });
    }

    @Test
    void savingStartsOff() {
        ArenaWorld arenaWorld = new ArenaWorld(recordingWorld());
        assertFalse(arenaWorld.isSavingEnabled());
        assertEquals(List.of("setAutoSave(false)"), calls);
    }

    @Test
    void editModeTurnsSavingOnThenSavesAndTurnsItOff() {
        ArenaWorld arenaWorld = new ArenaWorld(recordingWorld());
        calls.clear();

        arenaWorld.setSavingEnabled(true);
        assertTrue(arenaWorld.isSavingEnabled());
        assertEquals(List.of("setAutoSave(true)"), calls);

        calls.clear();
        arenaWorld.setSavingEnabled(false);
        assertFalse(arenaWorld.isSavingEnabled());
        assertEquals(List.of("save", "setAutoSave(false)"), calls);
    }

    @Test
    void repeatingTheCurrentStateDoesNothing() {
        ArenaWorld arenaWorld = new ArenaWorld(recordingWorld());
        calls.clear();
        arenaWorld.setSavingEnabled(false);
        arenaWorld.close();
        assertEquals(List.of(), calls);
    }

    @Test
    void closeSavesAnOpenEdit() {
        ArenaWorld arenaWorld = new ArenaWorld(recordingWorld());
        arenaWorld.setSavingEnabled(true);
        calls.clear();
        arenaWorld.close();
        assertEquals(List.of("save", "setAutoSave(false)"), calls);
    }
}
