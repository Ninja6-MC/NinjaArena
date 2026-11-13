package com.ninja6.ninjaarena.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.config.ReloadFiles;
import com.ninja6.ninjaarena.map.SeedSettings.Mode;
import com.ninja6.ninjaarena.map.SeedSettings.Regenerate;
import com.ninja6.ninjaarena.map.SeedSettings.Sharing;
import com.ninja6.ninjaarena.map.SeedSettings.SlotSettings;
import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SeedSettingsTest {

    private static SeedSettings parse(String yaml) {
        return SeedSettings.from(YamlConfiguration.loadConfiguration(new StringReader(yaml))
                .getConfigurationSection("maps"));
    }

    private static SeedSettings.Invalid invalid(String yaml) {
        return assertThrows(SeedSettings.Invalid.class, () -> parse(yaml));
    }

    @Test
    void theBundledSectionIsTheDefaultsRandomDistinctAndAMapPerMatch() {
        SeedSettings bundled = SeedSettings.from(ReloadFiles.defaults().getConfigurationSection("maps"));
        assertEquals(SeedSettings.DEFAULTS, bundled);
        assertEquals(Mode.RANDOM, bundled.defaults().mode());
        assertEquals(Sharing.DISTINCT, bundled.sharing());
        assertEquals(Regenerate.MATCH, bundled.defaults().regenerate());
        assertEquals(SeedSettings.DEFAULT_DIFFICULTY, bundled.defaults().difficulty());
        assertSame(SeedSettings.DEFAULTS, SeedSettings.from(null));
        assertEquals(SeedSettings.DEFAULTS, parse("maps: {}"));
    }

    @Test
    void readsEveryKeyAndASlotOverridesOnlyWhatItSets() {
        SeedSettings settings = parse("""
                maps:
                  default:
                    mode: rotation
                    seed: '7'
                    seeds: [1, 2, '3']
                    rotate-every: 30m
                    reroll-every: 2h
                    difficulty: 4
                    regenerate: round
                  slots:
                    2:
                      mode: static
                      seed: -9000000000
                    3:
                      difficulty: 1
                  seed-sharing: distinct
                """);
        SlotSettings defaults = new SlotSettings(Mode.ROTATION, OptionalLong.of(7), List.of(1L, 2L, 3L),
                Duration.ofMinutes(30), Duration.ofHours(2), 4, Regenerate.ROUND);
        assertEquals(defaults, settings.defaults());
        assertEquals(defaults, settings.forSlot(1));
        assertEquals(new SlotSettings(Mode.STATIC, OptionalLong.of(-9_000_000_000L), List.of(1L, 2L, 3L),
                Duration.ofMinutes(30), Duration.ofHours(2), 4, Regenerate.ROUND), settings.forSlot(2));
        assertEquals(1, settings.forSlot(3).difficulty());
        assertEquals(Mode.ROTATION, settings.forSlot(3).mode());
        assertEquals(defaults, settings.forSlot(4));
        assertTrue(settings.regeneratesRounds(1));
    }

    @Test
    void sharedIgnoresTheSlotOverrides() {
        SeedSettings settings = parse("""
                maps:
                  slots:
                    1: {mode: static, seed: 5}
                  seed-sharing: shared
                """);
        assertEquals(Map.of(1, settings.slots().get(1)), settings.slots(), "kept, to warn about");
        assertEquals(settings.defaults(), settings.forSlot(1));
    }

    @Test
    void regeneratingRoundsCountsOnlyTheSlotsInUse() {
        SeedSettings settings = parse("""
                maps:
                  slots:
                    3: {regenerate: round}
                """);
        assertFalse(settings.regeneratesRounds(2));
        assertTrue(settings.regeneratesRounds(3));
        assertFalse(SeedSettings.DEFAULTS.regeneratesRounds(100));
    }

    @Test
    void validationNamesTheKeyAndWhy() {
        assertEquals("maps.default.mode", invalid("maps: {default: {mode: shuffle}}").path());
        assertEquals("maps.seed-sharing", invalid("maps: {seed-sharing: some}").path());
        assertEquals("maps.default.regenerate", invalid("maps: {default: {regenerate: always}}").path());
        assertEquals("maps.default.difficulty", invalid("maps: {default: {difficulty: 6}}").path());
        assertEquals("maps.default.difficulty", invalid("maps: {default: {difficulty: 0}}").path());
        assertEquals("maps.default.difficulty", invalid("maps: {default: {difficulty: '2'}}").path());
        assertEquals("maps.default.seed", invalid("maps: {default: {seed: 1.5}}").path());
        assertEquals("maps.default.seed", invalid("maps: {default: {seed: ten}}").path());
        assertEquals("maps.default.seed", invalid("maps: {default: {seed: 99999999999999999999}}").path());
        assertEquals("maps.default.seeds", invalid("maps: {default: {seeds: 5}}").path());
        assertEquals("maps.default.seeds.1", invalid("maps: {default: {seeds: [1, x]}}").path());
        SeedSettings.Invalid twice = invalid("maps: {default: {seeds: [1, 1]}}");
        assertEquals("maps.default.seeds.1", twice.path());
        assertEquals("seed 1 is in the list twice", twice.reason());
        SeedSettings.Invalid duration = invalid("maps: {default: {rotate-every: 30}}");
        assertEquals("maps.default.rotate-every", duration.path());
        assertTrue(duration.reason().startsWith("expected a duration such as 30m"), duration.reason());
        assertEquals("maps.default.reroll-every", invalid("maps: {default: {reroll-every: 1h1h}}").path());
        assertEquals("maps.default.colour", invalid("maps: {default: {colour: red}}").path());
        assertEquals("maps.other", invalid("maps: {other: 1}").path());
        assertEquals("maps.slots.0", invalid("maps: {slots: {0: {mode: random}}}").path());
        assertEquals("maps.slots.101", invalid("maps: {slots: {101: {mode: random}}}").path());
        assertEquals("maps.slots.first", invalid("maps: {slots: {first: {mode: random}}}").path());
        assertEquals("maps.slots.2", invalid("maps: {slots: {2: random}}").path());
        assertEquals("maps.default", invalid("maps: {default: random}").path());
    }

    @Test
    void staticNeedsASeedAndRotationNeedsSeeds() {
        SeedSettings.Invalid noSeed = invalid("maps: {default: {mode: static}}");
        assertEquals("maps.default.mode", noSeed.path());
        assertEquals("static mode needs a seed", noSeed.reason());
        assertEquals("maps.slots.2.mode", invalid("maps: {slots: {2: {mode: rotation}}}").path());
        assertEquals("maps.default.seeds", invalid("maps: {default: {mode: rotation, seeds: []}}").path());
        // A slot inherits the default seed, so it can be static without setting one.
        assertEquals(OptionalLong.of(5), parse("maps: {default: {seed: 5}, slots: {2: {mode: static}}}")
                .forSlot(2).seed());
    }
}
