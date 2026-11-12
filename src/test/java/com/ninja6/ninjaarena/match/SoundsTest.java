package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.config.ConfigReload;
import com.ninja6.ninjaarena.config.ReloadFiles;
import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.sound.Sounds;
import com.ninja6.ninjaarena.sound.Sounds.Cue;
import com.ninja6.ninjaarena.tool.ToolSettings;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoundsTest extends MatchFixture {
    @TempDir Path dir;

    @Test
    void reloadChangesTheNextCueInAnExistingMatchAndBadValuesFallBack() throws Exception {
        startTwoOnTwo();
        var sounds = new Sounds(plugin.getServer()::getPlayer);
        var config = ReloadFiles.defaults();
        var warnings = new ArrayList<String>();
        var target = new ConfigReload.Target() {
            public boolean canResize() { return true; }
            public void world(ArenaWorldSettings value) { }
            public void grid(int size, double border) { }
            public void waiting(WaitingRoomSettings value) { }
            public void match(MatchSettings value, boolean friendlyFire, ScoringSettings scoring) { }
            public void tools(ToolSettings value) { }
            public void kit(KitDefinition value) { }
            public void configuration(ConfigurationSection value) {
                sounds.load(value, warnings::add);
            }
        };
        var reload = new ConfigReload(dir, config, KitDefinition.EMPTY, new KitItems(), target);
        Files.writeString(dir.resolve("kits.yml"), "format: 1\nitems: []\n");
        config.set("sounds.hazard-warning.sound", "minecraft:ui.button.click");
        config.set("sounds.hazard-warning.volume", 0.1);
        config.save(dir.resolve("config.yml").toFile());
        assertTrue(reload.reload().stream().anyMatch(line -> line.contains("sounds.hazard-warning.sound")));
        sounds.hazardWarning(matches.matchOf(ALICE).orElseThrow());
        assertEquals("ui.button.click", players.get(ALICE).sounds.getLast().name().value());
        assertEquals(0.1f, players.get(ALICE).sounds.getLast().volume());
        config.set("sounds.hazard-warning.sound", "minecraft:invalid");
        config.set("sounds.hazard-warning.volume", -1);
        config.save(dir.resolve("config.yml").toFile());
        reload.reload();
        assertEquals(2, warnings.size());
        sounds.hazardWarning(matches.matchOf(ALICE).orElseThrow());
        assertEquals(Cue.HAZARD_WARNING.defaultSound(), players.get(ALICE).sounds.getLast());
    }

    @Test
    void bundledValuesLoadQuietlyAndAllCuesCanBeSilenced() {
        Sounds sounds = new Sounds(plugin.getServer()::getPlayer);
        var config = ReloadFiles.defaults();
        List<String> warnings = new ArrayList<>();
        sounds.load(config, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        for (Cue cue : Cue.values()) {
            sounds.player(cue, player(ALICE));
            assertEquals(cue.defaultSound(), players.get(ALICE).sounds.getLast());
            config.set("sounds." + cue.key() + ".enabled", false);
        }
        int before = players.get(ALICE).sounds.size();
        sounds.load(config, warnings::add);
        for (Cue cue : Cue.values()) sounds.player(cue, player(ALICE));
        assertEquals(before, players.get(ALICE).sounds.size());
    }

    @Test
    void invalidFieldsWarnOnceAndFallBackIndividually() {
        Sounds sounds = new Sounds(plugin.getServer()::getPlayer);
        var config = ReloadFiles.defaults();
        config.set("sounds.enemy-spawn.sound", "minecraft:no_such_sound");
        config.set("sounds.enemy-spawn.volume", 3);
        config.set("sounds.enemy-spawn.pitch", Double.NaN);
        config.set("sounds.enemy-spawn.enabled", "false");
        List<String> warnings = new ArrayList<>();
        sounds.load(config, warnings::add);
        assertEquals(4, warnings.size());
        sounds.player(Cue.ENEMY_SPAWN, player(ALICE));
        sounds.player(Cue.ENEMY_SPAWN, player(ALICE));
        assertEquals(4, warnings.size());
        assertEquals(Cue.ENEMY_SPAWN.defaultSound(), players.get(ALICE).sounds.getLast());
    }

    @Test
    void matchCuesReachItsPlayersAndSpectatorsWithoutDuplicateOrLobbyDelivery() {
        startTwoOnTwo();
        matches.leave(player(DAVE), MatchService.Exit.COMMAND);
        matches.leave(player(CAROL), MatchService.Exit.COMMAND);
        var sounds = new Sounds(plugin.getServer()::getPlayer, arena -> List.of(CAROL, ALICE));
        sounds.hazardWarning(matches.matchOf(ALICE).orElseThrow());
        for (var id : List.of(ALICE, BOB, CAROL)) assertEquals(1, players.get(id).sounds.size());
        assertTrue(players.get(DAVE).sounds.isEmpty());
    }
}
