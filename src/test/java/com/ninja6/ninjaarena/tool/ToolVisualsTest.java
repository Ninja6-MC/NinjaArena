package com.ninja6.ninjaarena.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.support.TestTaggedItem;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.junit.jupiter.api.Test;

class ToolVisualsTest {
    private final UUID thrower = UUID.randomUUID();
    private final UUID rival = UUID.randomUUID();
    private final UUID spectator = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();
    private final Set<UUID> loaded = new HashSet<>(List.of(thrower, rival, spectator, outsider));
    private final List<UUID> world = new ArrayList<>(List.of(thrower, rival));
    private final List<Boolean> creations = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();
    private boolean flight = true;
    private final MatchView match = proxy(MatchView.class, (name, args) -> switch (name) {
        case "arena" -> "dojo";
        case "players" -> List.of(thrower, rival);
        case "teamOf" -> args[0].equals(thrower) ? 2 : 1;
        default -> throw new UnsupportedOperationException(name);
    });
    private final ItemStack vanilla = new TestTaggedItem(Material.SNOWBALL, Map.of(), 1);
    private final ToolVisuals visuals = new ToolVisuals(loaded::contains, () -> flight,
            arena -> List.of(spectator), id -> Optional.of(match), (tool, custom, color) -> {
                creations.add(custom);
                colors.add(color);
                return new TestTaggedItem(tool.material(), Map.of(NinjaTool.KEY, tool.key()), 1);
            }, () -> vanilla);

    @FunctionalInterface
    private interface Call { Object apply(String name, Object[] args); }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (p, method, args) -> call.apply(method.getName(), args));
    }

    private Player player(UUID id) {
        return proxy(Player.class, (name, args) -> {
            if (name.equals("getUniqueId")) return id;
            throw new UnsupportedOperationException(name);
        });
    }

    @Test
    void everyParticipantAndSpectatorCountsEvenOutsideTheWorld() {
        assertTrue(visuals.customFlight(match, List.of()));
        for (UUID id : List.of(thrower, rival, spectator)) {
            loaded.remove(id);
            assertFalse(visuals.customFlight(match, List.of()), id.toString());
            loaded.add(id);
        }
    }

    @Test
    void unrelatedWorldPlayersCountButOtherWorldPlayersDoNot() {
        loaded.remove(outsider);
        assertTrue(visuals.customFlight(match, world));
        world.add(outsider);
        assertFalse(visuals.customFlight(match, world));
        loaded.add(outsider);
        assertTrue(visuals.customFlight(match, world));
    }

    @Test
    void operatorFallbackAlwaysDisablesCustomFlight() {
        flight = false;
        assertFalse(visuals.customFlight(match, world));
        visuals.give(NinjaTool.DASH, player(thrower));
        assertEquals(List.of(true), creations, "flight fallback does not disable held artwork");
    }

    @Test
    void heldItemsFollowRecipientLoadedStateAndCurrentTeam() {
        visuals.give(NinjaTool.SHURIKEN, player(thrower));
        loaded.remove(thrower);
        visuals.give(NinjaTool.SMOKE_BOMB, player(thrower));
        assertEquals(List.of(true, false), creations);
        assertEquals(List.of(0x5555FF, 0x5555FF), colors);
    }

    @Test
    void operatorAssertionAllowsBedrockHeldModelsWithoutClaimingLoadedOrEnablingFlight() {
        loaded.remove(thrower);
        var asserted = new ToolVisuals(loaded::contains, id -> true, () -> true,
                arena -> List.of(spectator), id -> Optional.of(match), (tool, custom, color) -> {
                    creations.add(custom);
                    return vanilla;
                }, () -> vanilla);
        asserted.give(NinjaTool.SHURIKEN, player(thrower));
        assertEquals(List.of(true), creations);
        assertFalse(asserted.customFlight(match, world));
        assertFalse(loaded.contains(thrower));
        var optional = new ToolVisuals(loaded::contains, id -> false, () -> true,
                arena -> List.of(spectator), id -> Optional.of(match), (tool, custom, color) -> {
                    creations.add(custom);
                    return vanilla;
                }, () -> vanilla);
        optional.give(NinjaTool.SMOKE_BOMB, player(thrower));
        assertEquals(List.of(true, false), creations);
    }

    @Test
    void flightSetsAnExplicitVanillaStackAndDoesNotChangeItsDecisionLater() {
        List<ItemStack> assigned = new ArrayList<>();
        World arena = proxy(World.class, (name, args) -> {
            if (name.equals("getPlayers")) return world.stream().map(this::player).toList();
            throw new UnsupportedOperationException(name);
        });
        Snowball shot = proxy(Snowball.class, (name, args) -> switch (name) {
            case "getWorld" -> arena;
            case "setItem" -> { assigned.add((ItemStack) args[0]); yield null; }
            default -> throw new UnsupportedOperationException(name);
        });
        loaded.remove(spectator);
        visuals.flight(shot, NinjaTool.SHURIKEN, match, thrower);
        assertSame(vanilla, assigned.getFirst());
        assertTrue(creations.isEmpty());
        loaded.add(spectator);
        assertEquals(1, assigned.size(), "load status must not mutate an already flying shot");
        visuals.flight(shot, NinjaTool.SHURIKEN, match, thrower);
        assertEquals(List.of(true), creations);
        assertEquals(2, assigned.size());
        assertEquals(Optional.of(NinjaTool.SHURIKEN), NinjaTool.of(assigned.getLast()));
        assertEquals(1, assigned.getLast().getAmount());
    }

    @Test
    void appearanceUsesNamespacedModelAndOnlyTheFirstAccentColor() {
        for (NinjaTool tool : NinjaTool.values()) {
            Map<String, Object> changed = new java.util.HashMap<>();
            CustomModelDataComponent data = proxy(CustomModelDataComponent.class, (name, args) -> {
                changed.put(name, args[0]); return null;
            });
            ItemMeta meta = proxy(ItemMeta.class, (name, args) -> {
                if (name.equals("getCustomModelDataComponent")) return data;
                changed.put(name, args[0]); return null;
            });
            tool.appearance(meta, true, 0xFF5555);
            assertEquals(new NamespacedKey("ninjaarena", tool == NinjaTool.SMOKE_BOMB ? "smoke_bomb" : tool.key()),
                    changed.get("setItemModel"));
            assertEquals(List.of(Color.fromRGB(0xFF5555)), changed.get("setColors"));
            assertEquals(List.of("red"), changed.get("setStrings"));
            assertSame(data, changed.get("setCustomModelDataComponent"));
            changed.clear();
            tool.appearance(meta, false, 0xFF5555);
            assertTrue(changed.containsKey("setItemModel"));
            assertNull(changed.get("setItemModel"));
            assertEquals(1, changed.size());
        }
    }

    @Test
    void appearanceMarkersMatchEveryTeamAndNeutral() {
        assertEquals(List.of("white", "red", "blue", "green", "yellow"),
                java.util.stream.IntStream.rangeClosed(0, 4).map(ToolVisuals::teamColor)
                        .mapToObj(NinjaTool::accentMarker).toList());
    }
}
