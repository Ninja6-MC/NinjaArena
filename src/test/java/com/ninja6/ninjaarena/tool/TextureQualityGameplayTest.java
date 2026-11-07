package com.ninja6.ninjaarena.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.resourcepack.ResourcePackSettings;
import com.ninja6.ninjaarena.resourcepack.ResourcePackSettings.Pack;
import com.ninja6.ninjaarena.resourcepack.ResourcePacks;
import com.ninja6.ninjaarena.resourcepack.ResourcePacks.OverlayState;
import com.ninja6.ninjaarena.support.TestTaggedItem;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.junit.jupiter.api.Test;

/**
 * Quality changes nothing the game reads: held models and accents depend on the Standard base
 * and the team alone, and tools are recognised by their tag, whatever the overlay is doing.
 */
class TextureQualityGameplayTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Pack BASE = new Pack(UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
            "https://example.org/standard.zip", "a".repeat(40));
    private static final Pack DETAIL = new Pack(UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
            "https://example.org/detailed.zip", "b".repeat(40));

    private record Made(NinjaTool tool, boolean custom, int color, String tag) { }

    private final AtomicInteger team = new AtomicInteger(1);
    private final List<Made> made = new ArrayList<>();

    private ResourcePacks packs(boolean required) {
        return new ResourcePacks(new ResourcePackSettings(true, required, true, 120, BASE, Optional.of(DETAIL)),
                new ResourcePacks.Transport() {
                    @Override public void send(UUID player, Pack pack, boolean required) { }
                    @Override public void remove(UUID player, UUID pack) { }
                    @Override public void kick(UUID player, String message) { }
                    @Override public void error(String message, RuntimeException failure) { }
                }, () -> 0L);
    }

    private ToolVisuals visuals(ResourcePacks packs) {
        MatchView match = (MatchView) Proxy.newProxyInstance(MatchView.class.getClassLoader(),
                new Class<?>[] {MatchView.class}, (p, m, a) -> switch (m.getName()) {
                    case "teamOf" -> team.get();
                    case "arena" -> "dojo";
                    case "players" -> List.of(PLAYER);
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return new ToolVisuals(packs, () -> true, arena -> List.of(), id -> Optional.of(match),
                (tool, custom, color) -> {
                    made.add(new Made(tool, custom, color, tool.key()));
                    return new TestTaggedItem(tool.material(), Map.of(NinjaTool.KEY, tool.key()), 1);
                }, () -> new TestTaggedItem(Material.SNOWBALL, Map.of(), 1));
    }

    private Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (p, m, a) -> {
                    if (m.getName().equals("getUniqueId")) {
                        return PLAYER;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private List<Made> kitsAcrossTeams(ResourcePacks packs) {
        made.clear();
        ToolVisuals visuals = visuals(packs);
        for (int t = 0; t <= 4; t++) {
            team.set(t);
            for (NinjaTool tool : NinjaTool.values()) {
                visuals.give(tool, player());
            }
        }
        return List.copyOf(made);
    }

    @Test
    void heldToolsAndTeamAccentsAreIdenticalWhateverTheOverlayDoes() {
        for (boolean required : new boolean[] {true, false}) {
            ResourcePacks standard = packs(required);
            standard.join(PLAYER);
            standard.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
            List<Made> reference = kitsAcrossTeams(standard);
            assertEquals(5 * NinjaTool.values().length, reference.size());
            assertTrue(reference.stream().allMatch(Made::custom));

            for (OverlayState state : new OverlayState[] {OverlayState.PENDING, OverlayState.LOADED,
                    OverlayState.DECLINED, OverlayState.FAILED, OverlayState.CANCELLED}) {
                ResourcePacks packs = packs(required);
                packs.join(PLAYER);
                packs.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
                packs.addDetailed(PLAYER);
                switch (state) {
                    case LOADED -> packs.status(PLAYER, DETAIL.id(), Status.SUCCESSFULLY_LOADED);
                    case DECLINED -> packs.status(PLAYER, DETAIL.id(), Status.DECLINED);
                    case FAILED -> packs.status(PLAYER, DETAIL.id(), Status.FAILED_DOWNLOAD);
                    case CANCELLED -> packs.removeDetailed(PLAYER);
                    default -> { }
                }
                assertEquals(state, packs.overlay(PLAYER));
                assertEquals(reference, kitsAcrossTeams(packs), state.name());
            }
        }
    }

    @Test
    void teamChangesRecolourTheAccentAtBothQualities() {
        ResourcePacks packs = packs(true);
        packs.join(PLAYER);
        packs.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
        ToolVisuals visuals = visuals(packs);
        List<Integer> standard = new ArrayList<>();
        for (int t = 1; t <= 4; t++) {
            team.set(t);
            made.clear();
            visuals.give(NinjaTool.SHURIKEN, player());
            standard.add(made.getFirst().color());
        }
        assertEquals(List.of(0xFF5555, 0x5555FF, 0x55FF55, 0xFFFF55), standard);
        packs.addDetailed(PLAYER);
        packs.status(PLAYER, DETAIL.id(), Status.SUCCESSFULLY_LOADED);
        List<Integer> detailed = new ArrayList<>();
        for (int t = 1; t <= 4; t++) {
            team.set(t);
            made.clear();
            visuals.give(NinjaTool.SHURIKEN, player());
            detailed.add(made.getFirst().color());
        }
        assertEquals(standard, detailed);
    }

    @Test
    void toolsAreRecognisedByTheirTagAloneAtEveryQuality() {
        for (NinjaTool tool : NinjaTool.values()) {
            var item = new TestTaggedItem(tool.material(), Map.of(NinjaTool.KEY, tool.key()), 1);
            assertEquals(Optional.of(tool), NinjaTool.of(item));
        }
        var lookalike = new TestTaggedItem(NinjaTool.SHURIKEN.material(), Map.of(), 1);
        assertEquals(Optional.empty(), NinjaTool.of(lookalike));
    }
}
