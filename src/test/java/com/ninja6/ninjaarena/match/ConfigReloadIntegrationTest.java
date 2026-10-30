package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.config.ConfigReload;
import com.ninja6.ninjaarena.config.ReloadFiles;
import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.tool.ToolSettings;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class ConfigReloadIntegrationTest extends MatchFixture {

    @Test
    void reloadDuringRunningMatchValidatesBothFilesAndPreservesOldMatchWhileQueuingNewDefaults() throws Exception {
        var scores = new TeamScores();
        var awards = new TeamScoringListener(scores, ScoringSettings.DEFAULTS, plugin.getServer()::getPlayer);
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, player -> RestoreResult.RESTORED,
                event -> {
                    events.add(event);
                    switch (event) {
                        case PlayerEliminatedEvent out -> awards.onEliminated(out);
                        case RoundEndEvent end -> awards.onRoundEnd(end);
                        case MatchEndEvent end -> awards.onMatchEnd(end);
                        default -> { }
                    }
                }, player -> { });
        matches.setStandings(scores);
        var protection = new MatchProtectionListener(matches, plugin.getServer()::getPlayer, false, id -> false);
        var initial = ReloadFiles.defaults();
        initial.set("match.prep-seconds", SETTINGS.prepSeconds());
        initial.set("match.round-seconds", SETTINGS.roundSeconds());
        initial.set("match.overrun-limit-seconds", SETTINGS.overrunLimitSeconds());
        initial.set("match.round-end-seconds", SETTINGS.roundEndSeconds());
        initial.set("match.match-end-seconds", SETTINGS.matchEndSeconds());
        initial.set("match.rounds", SETTINGS.rounds());
        initial.set("match.extra-rounds", SETTINGS.extraRounds());
        var reloader = new ConfigReload(dir, initial, KitDefinition.EMPTY, new KitItems(), new ConfigReload.Target() {
            @Override public boolean canResize() { return arenas.canResize() && matches.matches().isEmpty(); }
            @Override public void match(MatchSettings settings, boolean friendlyFire, ScoringSettings scoring) {
                protection.setFriendlyFire(friendlyFire);
                awards.setSettings(scoring, matches.matches());
                matches.setSettings(settings);
            }
            @Override public void world(ArenaWorldSettings settings) { throw new AssertionError("world unchanged"); }
            @Override public void grid(int cellSize, double borderSize) { throw new AssertionError("grid unchanged"); }
            @Override public void waiting(WaitingRoomSettings settings) { throw new AssertionError("room unchanged"); }
            @Override public void tools(ToolSettings settings) { throw new AssertionError("tools unchanged"); }
            @Override public void kit(KitDefinition definition) { throw new AssertionError("kit unchanged"); }
        });
        startTwoOnTwo();
        initial.set("match.prep-seconds", 1);
        initial.set("match.rounds", 1);
        initial.set("match.extra-rounds", 0);
        initial.set("match.friendly-fire", true);
        initial.set("scoring.round-win-points", 50);
        initial.set("scoring.player-kill-points", 20);
        Files.writeString(dir.resolve("config.yml"), initial.saveToString());
        Files.writeString(dir.resolve("kits.yml"), "format: 1\nitems:\n  - slot: 99\n    material: WOODEN_SWORD\n");
        assertTrue(reloader.reload().getFirst().contains("kits.yml: line 3"));
        assertEquals(SETTINGS, matches.match("dojo").orElseThrow().settings());
        Files.writeString(dir.resolve("kits.yml"), "format: 1\nitems: []\n");
        assertTrue(reloader.reload().get(2).contains("next match"));
        assertEquals(SETTINGS, matches.match("dojo").orElseThrow().settings());
        ticks(2);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(ALICE));
        runNextTick();
        assertEquals(9, scores.score(matches.match("dojo").orElseThrow(), 1));
        matches.close();
        startTwoOnTwo();
        assertEquals(1, matches.match("dojo").orElseThrow().settings().prepSeconds());
        assertEquals(1, matches.match("dojo").orElseThrow().settings().rounds());
        assertEquals(0, matches.match("dojo").orElseThrow().settings().extraRounds());
        ticks(1);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(ALICE));
        runNextTick();
        assertEquals(90, scores.score(matches.match("dojo").orElseThrow(), 1));
    }
}
