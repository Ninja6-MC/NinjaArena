package com.ninja6.ninjaarena.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class LobbyWorldTest {

    private final Map<String, Object> rules = new HashMap<>();
    private final List<String> calls = new ArrayList<>();

    /** Records rules and settings; a border or distance change fails the test. */
    private World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setGameRule" -> {
                        rules.put(((GameRule<?>) args[0]).getKey().getKey(), args[1]);
                        yield true;
                    }
                    case "setAutoSave", "setTime", "setStorm", "setThundering" -> {
                        calls.add(method.getName() + ":" + args[0]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void theLobbyIsSavedAndHasTheQuietRulesOfTheArenaWorld() {
        World world = world();
        LobbyWorld lobby = new LobbyWorld(world);

        assertSame(world, lobby.world());
        assertEquals(List.of("setAutoSave:true", "setTime:" + ArenaWorld.FIXED_TIME, "setStorm:false",
                "setThundering:false"), calls);
        assertEquals(true, rules.get("keep_inventory"));
        for (String off : List.of("advance_time", "advance_weather", "spawn_mobs", "spawn_monsters", "spawn_patrols",
                "spawn_phantoms", "spawn_wandering_traders", "spawn_wardens")) {
            assertEquals(false, rules.get(off), off);
        }
        assertEquals(9, rules.size());
    }
}
