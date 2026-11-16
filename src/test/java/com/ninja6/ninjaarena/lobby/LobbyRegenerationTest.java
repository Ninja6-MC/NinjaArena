package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/** When the generated lobby is placed, and how a server with the obsidian build moves to it. */
class LobbyRegenerationTest {

    private final MemorySite site = new MemorySite();
    private final List<String> log = new ArrayList<>();
    private final List<String> built = new ArrayList<>();
    private int occupants;
    private boolean held;

    private LobbyBuild start(int maxActive) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                log.add(record.getMessage());
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        });
        LobbyBuild build = new LobbyBuild(LobbyFixture.pieces(), site, logger, () -> occupants, () -> held,
                () -> built.add("built"));
        build.start(maxActive);
        return build;
    }

    private static void finish(LobbyBuild build) {
        while (!build.tick(Long.MAX_VALUE, System::nanoTime)) {
            assertTrue(build.building());
        }
    }

    @Test
    void aNewWorldIsGeneratedOnceNobodyIsThereAndKeptOnEveryLaterStart() {
        occupants = 1;
        LobbyBuild build = start(4);
        assertTrue(build.generated());
        assertTrue(build.pending());
        assertEquals(4, build.layout().pads().size());
        assertFalse(build.poll(), "never while anyone is in the lobby world");
        assertTrue(site.world.isEmpty());

        occupants = 0;
        assertTrue(build.poll());
        LobbyBuild.Saved building = LobbyBuild.Saved.decode(site.saved).orElseThrow();
        assertFalse(building.done());
        assertEquals(build.layout().bounds(), building.sweep(), "the swept box is recorded with the build");
        finish(build);
        assertEquals(List.of("built"), built);
        assertEquals(new LobbyBuild.Saved(4, site.seed, true), LobbyBuild.Saved.decode(site.saved).orElseThrow());
        assertFalse(build.pending());

        // Restarts keep it, admin edits included.
        site.set(0, 70, 0, "minecraft:obsidian");
        long writes = site.writes;
        for (int restart = 0; restart < 3; restart++) {
            LobbyBuild again = start(4);
            assertFalse(again.pending());
            assertFalse(again.poll());
            assertEquals(build.layout(), again.layout());
        }
        assertEquals(writes, site.writes);
        assertEquals("minecraft:obsidian", site.at(0, 70, 0));
    }

    @Test
    void aMaxActiveChangeRegeneratesOnlyWhenThePadCountChangesAndTheLobbyWorldIsEmpty() {
        LobbyBuild build = start(4);
        build.poll();
        finish(build);
        site.set(0, 70, 0, "minecraft:obsidian");

        build.maxActive(4);
        assertFalse(build.pending());
        occupants = 3;
        build.maxActive(9);
        assertTrue(build.pending());
        for (int second = 0; second < 5; second++) {
            assertFalse(build.poll(), "members, shop phases and spectators hold it back");
        }
        assertEquals(4, build.layout().pads().size());

        occupants = 0;
        assertTrue(build.poll());
        assertEquals(9, build.layout().pads().size(), "the new layout is in use while it is placed");
        finish(build);
        assertNull(site.at(0, 70, 0), "regenerating replaces admin edits inside the generated area");
        assertEquals(9, LobbyBuild.Saved.decode(site.saved).orElseThrow().pads());

        // Above 32 slots the lobby keeps 32 pads, so 40 to 50 changes nothing.
        build.maxActive(40);
        assertTrue(build.poll());
        finish(build);
        build.maxActive(50);
        assertFalse(build.pending());
    }

    @Test
    void aStartThatFindsADifferentPadCountOrAnUnfinishedBuildPlacesItAgain() {
        site.saved = new LobbyBuild.Saved(8, site.seed, true).encode();
        LobbyBuild changed = start(2);
        assertEquals(8, changed.layout().pads().size());
        assertTrue(changed.pending());

        site.saved = new LobbyBuild.Saved(8, site.seed, false).encode();
        LobbyBuild unfinished = start(8);
        assertTrue(unfinished.pending());
        assertTrue(log.stream().anyMatch(line -> line.contains("not finished")), log.toString());
    }

    @Test
    void anObsidianLobbyStaysWithANoticeLoggedOnceUntilReset() {
        site.legacy = true;
        site.set(-20, 64, -16, "minecraft:obsidian");
        site.set(-24, 70, 20, "minecraft:glass");
        LobbyBuild build = start(4);
        assertFalse(build.generated());
        assertEquals(LobbyLayout.bundled(), build.layout());
        assertFalse(build.pending());
        assertEquals(List.of(LobbyBuild.LEGACY_NOTICE), log);

        build.maxActive(8);
        assertFalse(build.pending(), "nothing is replaced automatically");
        assertFalse(build.poll());
        LobbyBuild restarted = start(8);
        assertEquals(1, log.stream().filter(LobbyBuild.LEGACY_NOTICE::equals).count(), "the notice is shown once");
        assertEquals("minecraft:obsidian", site.at(-20, 64, -16));

        List<String> told = new ArrayList<>();
        restarted.reset(sender(told));
        assertTrue(restarted.building());
        assertFalse(site.legacy, "a stop mid-way places the generated lobby again, not the obsidian one");
        finish(restarted);
        assertTrue(restarted.generated());
        assertEquals(8, restarted.layout().pads().size());
        assertNull(site.at(-20, 64, -16), "the obsidian build is cleared");
        assertNull(site.at(-24, 70, 20), "everything in the old bounds is cleared");
        assertEquals(List.of("Generating the lobby with 8 room pads.",
                "The lobby is generated with 8 room pads. Changes made in edit mode are gone."), told);
        assertFalse(start(8).pending());
    }

    @Test
    void anAdminResetWaitsWhileAnyoneIsInTheLobbyWorld() {
        LobbyBuild build = start(4);
        build.poll();
        finish(build);
        occupants = 2;
        List<String> told = new ArrayList<>();
        build.reset(sender(told));
        assertFalse(build.building());
        assertEquals(List.of(LobbyEditingTest.QUEUED), told);
        occupants = 0;
        assertTrue(build.poll());
        finish(build);
        assertEquals(3, told.size());
    }

    /** Every block in the world is inside the box. */
    private void onlyInside(com.ninja6.ninjaarena.arena.ArenaBounds box) {
        long outside = site.world.keySet().stream().filter(key -> {
            int x = (int) (key >> 38) << 6 >> 6;
            int z = (int) ((key >> 12) & 0x3FFFFFF) << 6 >> 6;
            int y = (int) (key & 0xFFF) << 20 >> 20;
            return !box.contains(x, y, z);
        }).count();
        assertEquals(0, outside, "blocks left outside " + box.describe());
    }

    @Test
    void aBuildStoppedHalfwayAfterLoweringThePadsStillClearsTheOldLobbyWhenResumed() {
        LobbyBuild build = start(32);
        build.poll();
        finish(build);
        build.maxActive(4);
        assertTrue(build.poll());
        site.failAfter = site.writes + 5_000;
        build.tick(Long.MAX_VALUE, System::nanoTime);
        assertFalse(build.building(), "the server stopped mid-way");
        site.failAfter = Long.MAX_VALUE;

        LobbyBuild resumed = start(4);
        assertTrue(resumed.pending());
        assertTrue(resumed.poll());
        finish(resumed);
        onlyInside(resumed.layout().build());
        assertEquals(GeneratedLobby.plan(4, site.seed, LobbyFixture.pieces().trees().size())
                .blueprint(LobbyFixture.pieces()).blocks(), site.world);
    }

    @Test
    void anObsidianReplacementStoppedHalfwayStillClearsTheObsidianBoundsWhenResumed() {
        site.legacy = true;
        site.set(-24, 70, 20, "minecraft:glass");
        site.set(24, 56, -20, "minecraft:obsidian");
        site.set(-20, 64, -16, "minecraft:obsidian");
        LobbyBuild build = start(4);
        build.reset(sender(new ArrayList<>()));
        site.failAfter = site.writes + 300;
        build.tick(Long.MAX_VALUE, System::nanoTime);
        assertFalse(build.building());
        site.failAfter = Long.MAX_VALUE;
        assertFalse(site.legacy);

        LobbyBuild resumed = start(4);
        assertTrue(resumed.poll());
        finish(resumed);
        assertNull(site.at(-24, 70, 20));
        assertNull(site.at(24, 56, -20));
        onlyInside(resumed.layout().build());
    }

    @Test
    void aRunningMatchOrARoomMemberAwayHoldsTheBuildBack() {
        LobbyBuild build = start(4);
        held = true;
        assertFalse(build.poll());
        assertTrue(build.pending());
        held = false;
        assertTrue(build.poll());
        assertTrue(site.held.size() == 1, "the swept chunks are held while it runs");
        finish(build);
        assertTrue(site.held.isEmpty(), "and let go after");
    }

    @Test
    void everyAdminWhoQueuedAResetIsToldAndAQueuedResetSurvivesARestart() {
        LobbyBuild build = start(4);
        build.poll();
        finish(build);
        occupants = 1;
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        build.reset(sender(first));
        build.reset(sender(second));
        assertTrue(site.resetQueued);

        // A restart keeps it queued.
        LobbyBuild restarted = start(4);
        assertTrue(restarted.pending());
        assertTrue(log.stream().anyMatch(line -> line.contains("queued before the restart")), log.toString());
        assertFalse(restarted.poll());

        occupants = 0;
        assertTrue(build.poll());
        finish(build);
        assertEquals(3, first.size());
        assertEquals(first, second);
        assertFalse(site.resetQueued);
    }

    @Test
    void aNewWorldStartsAtEnableBeforeAnyoneJoinsAndRefusesEntryUntilDone() {
        LobbyBuild build = start(4);
        assertTrue(build.poll(), "nobody can be there yet");
        assertTrue(build.building());
        // Players logging in now cannot be put there; occupancy does not stop a build in progress.
        occupants = 3;
        finish(build);
        assertFalse(build.building());
    }

    private static CommandSender sender(List<String> told) {
        return (CommandSender) java.lang.reflect.Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "sendMessage" -> {
                        told.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
