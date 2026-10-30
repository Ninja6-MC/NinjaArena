package com.ninja6.ninjaarena.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.match.MatchSettings;
import com.ninja6.ninjaarena.match.ScoringSettings;
import com.ninja6.ninjaarena.tool.NinjaTools;
import com.ninja6.ninjaarena.tool.ToolSettings;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigReloadTest {
    @TempDir Path dir;
    private static final String EMPTY_KIT = "format: 1\nitems: []\n";
    private final KitItems items = new KitItems();
    private final Runtime target = new Runtime();
    private ConfigReload reload;

    private static final class Runtime implements ConfigReload.Target {
        final List<String> calls = new ArrayList<>();
        boolean empty = true;
        boolean failWorld;
        ArenaWorldSettings world = ArenaWorldSettings.DEFAULTS;
        int cellSize = 256;
        WaitingRoomSettings waiting;
        MatchSettings match;
        boolean friendlyFire;
        ScoringSettings scoring;
        ToolSettings tools;
        KitDefinition kit;
        ConfigurationSection published;
        @Override public boolean canResize() { return empty; }
        @Override public void world(ArenaWorldSettings settings) {
            calls.add("world"); world = new ArenaWorldSettings(world.borderSize(), settings.viewDistance(), settings.simulationDistance());
            if (failWorld) { failWorld = false; throw new IllegalStateException("simulation setter failed"); }
        }
        @Override public void grid(int cellSize, double borderSize) {
            calls.add("grid"); this.cellSize = cellSize;
            world = new ArenaWorldSettings(borderSize, world.viewDistance(), world.simulationDistance());
        }
        @Override public void waiting(WaitingRoomSettings settings) { calls.add("waiting"); waiting = settings; }
        @Override public void match(MatchSettings settings, boolean friendlyFire, ScoringSettings scoring) {
            calls.add("match"); match = settings; this.friendlyFire = friendlyFire; this.scoring = scoring;
        }
        @Override public void tools(ToolSettings settings) { calls.add("tools"); tools = settings; }
        @Override public void kit(KitDefinition kit) { calls.add("kit"); this.kit = kit; }
        @Override public void configuration(ConfigurationSection settings) { published = settings; }
    }

    @BeforeEach
    void setup() throws Exception {
        NinjaTools.registerItems(items);
        write("", EMPTY_KIT);
        reload = new ConfigReload(dir, ReloadFiles.defaults(), KitDefinition.EMPTY, items, target);
    }

    private void write(String config, String kit) throws Exception {
        Files.writeString(dir.resolve("config.yml"), config);
        Files.writeString(dir.resolve("kits.yml"), kit);
    }

    private ReloadFiles.Candidate candidate() throws Exception {
        return ReloadFiles.read(dir.resolve("config.yml"), dir.resolve("kits.yml"), items);
    }

    @Test
    void bundledSchemaAndKitValidateWithoutCallingItemProviders() throws Exception {
        Files.writeString(dir.resolve("config.yml"), ReloadFiles.defaults().saveToString());
        Files.writeString(dir.resolve("kits.yml"), "format: 1\nitems:\n  - slot: 1\n    id: ninjaarena:shuriken\n    amount: 8\n");
        assertEquals(8, candidate().kit().items().getFirst().amount());
        assertTrue(reload.reload().get(2).contains("kits.yml (next round)"));
    }

    @Test
    void everyConfigLeafHasStrictTypeValidation() throws Exception {
        var defaults = ReloadFiles.defaults();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            var invalid = ReloadFiles.defaults();
            invalid.set(key, defaults.get(key) instanceof Boolean ? "false" : "bad-number");
            write(invalid.saveToString(), EMPTY_KIT);
            var failure = assertThrows(ReloadYaml.Invalid.class, this::candidate, key);
            assertTrue(failure.getMessage().contains(key), failure.getMessage());
        }
    }

    @Test
    void oneBadValueRejectsBothFilesBeforeAnyImmediateOrPostponedChange() throws Exception {
        String changed = "waiting-room:\n  min-players: 3\ntools:\n  shuriken:\n    damage: 7.0\narena-world:\n  view-distance: 9\n";
        write(changed, "format: 1\nitems:\n  - slot: 1\n    id: missing:item\n");
        assertTrue(reload.reload().getFirst().contains("kits.yml: line 4"));
        assertTrue(target.calls.isEmpty());
        assertEquals(null, target.published);
        write(changed, EMPTY_KIT);
        var report = reload.reload();
        assertEquals(List.of("world", "waiting", "tools"), target.calls);
        assertEquals(3, target.waiting.minPlayers());
        assertEquals(7.0, target.tools.shurikenDamage());
        assertEquals(9, target.world.viewDistance());
        assertTrue(report.get(1).contains("waiting-room.min-players"));
    }

    @Test
    void reloadReportsEveryTableGroupAndPublishesOnlyAcceptedValues() throws Exception {
        write("""
                waiting-room:
                  min-players: 4
                  countdown-seconds: 18
                  radius: 12
                match:
                  prep-seconds: 9
                  round-seconds: 60
                  overrun-limit-seconds: 0
                  round-end-seconds: 2
                  match-end-seconds: 3
                  rounds: 3
                  extra-rounds: 2
                  friendly-fire: true
                scoring:
                  round-win-points: 7
                  player-kill-points: 3
                tools:
                  dash:
                    strength: 1.5
                arena-world:
                  view-distance: 10
                  simulation-distance: 8
                """, "format: 1\nitems:\n  - slot: 1\n    id: ninjaarena:dash\n");
        var report = reload.reload();
        assertEquals(List.of("world", "waiting", "match", "tools", "kit"), target.calls);
        assertEquals(new WaitingRoomSettings(4, 18, 12), target.waiting);
        assertEquals(new MatchSettings(9, 60, 0, 2, 3, 3, 2), target.match);
        assertTrue(target.friendlyFire);
        assertEquals(new ScoringSettings(7, 3), target.scoring);
        assertEquals(1.5, target.tools.dashStrength());
        assertEquals(10, target.world.viewDistance());
        assertEquals(8, target.world.simulationDistance());
        assertTrue(report.get(2).contains("match.friendly-fire (next match)"));
        assertTrue(report.get(2).contains("scoring.round-win-points (next match)"));
        assertTrue(report.get(2).contains("kits.yml (next round)"));
        assertEquals("Refused: none.", report.get(3));
        target.calls.clear();
        assertEquals(List.of("The Arena reload complete.", "Changed: none.", "Postponed: none.", "Refused: none."), reload.reload());
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void gridAndBorderAreRefusedWhenOccupiedButDistancesApplyAndCanResizeWhenEmpty() throws Exception {
        target.empty = false;
        write("arena-grid:\n  cell-size: 512\narena-world:\n  border-size: 4096\n  view-distance: 12\n", EMPTY_KIT);
        var report = reload.reload();
        assertTrue(report.get(3).contains("arena-grid.cell-size"));
        assertTrue(report.get(3).contains("arena-world.border-size"));
        assertEquals(2048, target.world.borderSize());
        assertEquals(12, target.world.viewDistance());
        assertEquals(256, target.cellSize);
        assertEquals(256, target.published.getInt("arena-grid.cell-size"));
        target.calls.clear();
        assertTrue(reload.reload().get(3).contains("restart required"));
        assertTrue(target.calls.isEmpty());
        target.empty = true;
        report = reload.reload();
        assertEquals(List.of("grid"), target.calls);
        assertEquals(512, target.cellSize);
        assertEquals(4096, target.world.borderSize());
        assertEquals("Refused: none.", report.get(3));
    }

    @Test
    void invalidGridIsRejectedEvenWhenItWouldHaveBeenRefusedByPolicy() throws Exception {
        target.empty = false;
        write("arena-grid:\n  cell-size: 513\nwaiting-room:\n  radius: 10\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("config.yml: line 2"));
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void worldSetterFailureRollsBackBeforeOtherServicesChange() throws Exception {
        target.failWorld = true;
        write("arena-world:\n  view-distance: 12\nwaiting-room:\n  radius: 10\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("Old settings were restored"));
        assertEquals(List.of("world", "world"), target.calls);
        assertEquals(ArenaWorldSettings.DEFAULTS, target.world);
        assertEquals(null, target.waiting);
        assertEquals(null, target.published);
        target.calls.clear();
        assertTrue(reload.reload().get(1).contains("arena-world.view-distance"));
        assertEquals(List.of("world", "waiting"), target.calls);
    }

    @Test
    void settingsOutsideTheReloadTableRemainRestartOnly() throws Exception {
        write("director:\n  debug: true\nstability:\n  max-concurrent-matches: 5\n", EMPTY_KIT);
        assertTrue(reload.reload().get(3).contains("director.debug (restart required)"));
        assertTrue(reload.reload().get(3).contains("stability.max-concurrent-matches (restart required)"));
        assertTrue(target.calls.isEmpty());
        assertFalse(target.published.getBoolean("director.debug"));
    }

    @Test
    void missingFilesAreRejectedWithoutFallbackAndArenaDataIsNeverReloaded() throws Exception {
        Files.delete(dir.resolve("kits.yml"));
        assertTrue(reload.reload().getFirst().contains("kits.yml: line 1: cannot read"));
        write("", EMPTY_KIT);
        Files.delete(dir.resolve("config.yml"));
        assertTrue(reload.reload().getFirst().contains("config.yml: line 1: cannot read"));
        assertTrue(target.calls.isEmpty());
        for (String name : List.of("arenas.yml", "portals.yml", "snapshot.dat")) Files.writeString(dir.resolve(name), "unchanged");
        write("waiting-room:\n  radius: 8\n", EMPTY_KIT);
        reload.reload();
        for (String name : List.of("arenas.yml", "portals.yml", "snapshot.dat")) assertEquals("unchanged", Files.readString(dir.resolve(name)));
    }

    @Test
    void errorsNameActualLinesForUnknownDuplicateAndMissingFields() throws Exception {
        var badConfig = Map.of(
                "unknown:\n  child: 4\n", 1,
                "match:\n  rounds: 2\n  rounds: 3\n", 3,
                "# comment\nwaiting-room: 4\n", 2,
                "# comment\nstability:\n  pause-spawning-above-mspt: 20.0\n", 3,
                "# comment\n? [bad, key]\n: 5\n", 2);
        for (var entry : badConfig.entrySet()) {
            write(entry.getKey(), EMPTY_KIT);
            assertTrue(reload.reload().getFirst().contains("config.yml: line " + entry.getValue() + ":"), entry.toString());
        }
        write("", "format: 1\nitems:\n  - id: ninjaarena:dash\n");
        assertTrue(reload.reload().getFirst().contains("kits.yml: line 3: items.0.slot"));
    }

    @Test
    void nonMappingRootsReportTheirSourceLineWithoutChangingSettings() throws Exception {
        String invalidRoot = "# comment\n\n# another\n- invalid-root\n";
        write(invalidRoot, EMPTY_KIT);
        assertEquals("The Arena reload was rejected: config.yml: line 4: expected a mapping. Nothing changed.",
                reload.reload().getFirst());
        assertTrue(target.calls.isEmpty());

        write("", invalidRoot);
        assertEquals("The Arena reload was rejected: kits.yml: line 4: expected a mapping. Nothing changed.",
                reload.reload().getFirst());
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void invalidKitsRejectUnknownKeysDuplicateSlotsNonItemsAndExcessiveAmounts() throws Exception {
        for (String entry : List.of("slot: 1\n    material: WATER", "slot: 1\n    material: WOODEN_SWORD\n    amount: 2",
                "slot: 1\n    id: ninjaarena:dash\n    amount: 2", "slot: 1\n    id: ninjaarena:dash\n    other: 1",
                "slot: 1\n    id: ninjaarena:dash\n  - slot: 1\n    id: ninjaarena:dash")) {
            write("", "format: 1\nitems:\n  - " + entry + "\n");
            assertThrows(ReloadYaml.Invalid.class, this::candidate, entry);
        }
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void numericAndBooleanYamlTypesDoNotSilentlyClampOrCoerceStrings() throws Exception {
        write("match:\n  rounds: 0x14\n  friendly-fire: yes\n", EMPTY_KIT);
        assertEquals(20, candidate().config().getInt("match.rounds"));
        assertTrue(candidate().config().getBoolean("match.friendly-fire"));
        for (String config : List.of("match:\n  rounds: 2.0\n", "match:\n  rounds: 26\n",
                "match:\n  friendly-fire: 'false'\n", "tools:\n  shuriken:\n    damage: .nan\n",
                "tools:\n  shuriken:\n    damage: .inf\n", "match:\n  rounds: 999999999999999999999999\n")) {
            write(config, EMPTY_KIT);
            assertThrows(ReloadYaml.Invalid.class, this::candidate, config);
        }
    }

    @Test
    void declaredProviderLimitsValidateWithoutInvokingPlayerFactories() throws Exception {
        items.register("test:declared", 16, player -> { throw new AssertionError("must not create items on reload"); });
        items.register("test:legacy", player -> { throw new AssertionError("must not create items on reload"); });
        write("", "format: 1\nitems:\n  - slot: 1\n    id: test:declared\n    amount: 16\n");
        assertEquals(16, candidate().kit().items().getFirst().amount());
        write("", "format: 1\nitems:\n  - slot: 1\n    id: test:legacy\n    amount: 2\n");
        assertTrue(assertThrows(ReloadYaml.Invalid.class, this::candidate).getMessage().contains("stack limit of 1"));
        write("", "format: 1\nitems:\n  - slot: 1\n    material: WOODEN_SWORD\n");
        assertEquals(1, candidate().kit().items().getFirst().amount());
    }
}
