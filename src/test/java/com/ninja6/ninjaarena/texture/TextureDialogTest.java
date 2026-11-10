package com.ninja6.ninjaarena.texture;

import static com.ninja6.ninjaarena.texture.TextureFixture.ALICE;
import static com.ninja6.ninjaarena.texture.TextureFixture.BOB;
import static com.ninja6.ninjaarena.texture.TextureFixture.FLOOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import com.ninja6.ninjaarena.resourcepack.TextureMenuMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TextureDialogTest {

    /** Records what the dialog would show and lets a test press the buttons of any shown dialog. */
    private static final class Fake implements TextureDialog.Screens {
        record Shown(Player player, TextureDialog.Content content, TextureDialog.Press press) { }

        final List<Shown> shown = new ArrayList<>();
        final Map<UUID, Integer> closes = new HashMap<>();
        RuntimeException failShow;

        @Override
        public void show(Player player, TextureDialog.Content content, TextureDialog.Press press) {
            if (failShow != null) {
                throw failShow;
            }
            shown.add(new Shown(player, content, press));
        }

        @Override
        public void close(Player player) {
            closes.merge(player.getUniqueId(), 1, Integer::sum);
        }

        Shown last() {
            return shown.getLast();
        }
    }

    private TextureFixture fixture;
    private MenuHarness chest;
    private Fake fake;
    private TextureDialog dialog;
    private final List<String> told = new ArrayList<>();

    private final java.util.Set<UUID> inMatch = new java.util.HashSet<>();

    private void start(boolean authenticated) {
        fixture = new TextureFixture(true, true);
        fixture.authenticated = authenticated;
        chest = new MenuHarness(fixture);
        fake = new Fake();
        dialog = new TextureDialog(fixture.service, fake, (id, message) -> told.add(message), inMatch::contains);
        joined(ALICE);
        joined(BOB);
    }

    @AfterEach
    void close() {
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
    }

    private void joined(UUID id) {
        fixture.join(id);
        fixture.loadBase(id);
        fixture.settle();
    }

    private void press(Fake.Shown shown, Player presser, TextureDialog.Button button) {
        shown.press().pressed(presser, button);
    }

    @Test
    void anUnsavedDefaultHasThreeShortLinesAndChoiceNamesOnlyOnButtons() {
        start(true);
        dialog.open(chest.player(ALICE));
        var content = fake.last().content();
        assertEquals("Arena Texture Quality", content.title());
        assertEquals("Standard 16x16", content.standard());
        assertEquals("Detailed 32x32", content.detailed());
        assertEquals(List.of("Standard (default, not saved yet)", "Standard active.",
                "Applies now, or reconnect if needed."), content.lines());
        assertCompact(content);
    }

    private void assertCompact(TextureDialog.Content content) {
        assertEquals(3, content.lines().size());
        assertTrue(content.lines().stream().allMatch(line -> line.length() <= 50));
        String text = String.join("\n", content.lines());
        assertFalse(text.contains("[SELECTED]"));
        assertFalse(text.contains("Click to choose"));
        assertFalse(text.contains("16x16"));
        assertFalse(text.contains("32x32"));
    }

    @Test
    void aSavedStandardIsDistinguishedFromTheUnsavedDefault() {
        start(true);
        fixture.choose(ALICE, Quality.STANDARD);
        dialog.open(chest.player(ALICE));
        assertEquals("Saved choice: Standard", fake.last().content().lines().getFirst());
        assertCompact(fake.last().content());
    }

    @Test
    void aSessionOnlyChoiceNeverClaimsToBeSaved() {
        start(false);
        fixture.choose(ALICE, Quality.DETAILED);
        dialog.open(chest.player(ALICE));
        assertEquals(List.of("This connection only (not saved): Detailed",
                "Standard active; Detailed loading.", "For this connection; resets on leave."),
                fake.last().content().lines());
        assertCompact(fake.last().content());
    }

    @Test
    void bedrockUsesCompactDeliveryAndKeepsDiagnosticsOutOfTheBodyEvenAfterSaving() {
        start(true);
        fixture.bedrockPlayers.add(FLOOD);
        fixture.xuids.put(FLOOD, "2535400000001");
        fixture.bedrockActive.put(FLOOD, Quality.STANDARD);
        joined(FLOOD);
        dialog.open(chest.player(FLOOD));
        assertEquals(List.of("Standard (default, not saved yet)", "Standard sent on connect.",
                "Changes apply next connection."), fake.last().content().lines());
        press(fake.last(), chest.player(FLOOD), TextureDialog.Button.DETAILED);
        fixture.settle();
        assertEquals(List.of("Saved choice: Detailed", "Standard sent on connect.",
                "Changes apply next connection."), fake.last().content().lines());
        assertCompact(fake.last().content());
        assertTrue(fixture.service.describe(FLOOD).delivery().contains("Pack loading cannot be observed"));
        assertTrue(told.getLast().contains("Pack loading cannot be observed"));
    }

    @Test
    void aFailedSaveStaysCompactAndDoesNotClaimThatTheDefaultWasSaved() {
        start(true);
        fixture.backend.failWrites = true;
        dialog.open(chest.player(ALICE));
        press(fake.last(), chest.player(ALICE), TextureDialog.Button.DETAILED);
        fixture.settle();
        assertEquals("Standard (default, not saved yet)", fake.last().content().lines().getFirst());
        assertEquals("Could not save. Try again later.", fake.last().content().lines().get(1));
        assertCompact(fake.last().content());
    }

    @Test
    void aPressSavesThroughTheServiceShowsSavingThenTheResultAndTellsTheChat() {
        start(true);
        Player alice = chest.player(ALICE);
        dialog.open(alice);
        press(fake.last(), alice, TextureDialog.Button.DETAILED);
        assertTrue(String.join("\n", fake.last().content().lines()).contains("Saving..."));
        fixture.settle();
        String text = String.join("\n", fake.last().content().lines());
        assertFalse(text.contains("Saving..."));
        assertTrue(text.contains("Saved choice: Detailed"));
        assertTrue(told.getLast().startsWith("Saved: Detailed."));
        assertEquals(Quality.DETAILED, fixture.backend.rows.get(PlayerKey.java(ALICE)).quality());
        assertEquals(1, fixture.wire.overlayOffers());
    }

    @Test
    void aSecondPressWhileSavingIsIgnored() {
        start(true);
        Player alice = chest.player(ALICE);
        dialog.open(alice);
        var shown = fake.last();
        press(shown, alice, TextureDialog.Button.DETAILED);
        press(shown, alice, TextureDialog.Button.STANDARD);
        assertEquals("Still saving your last choice. Wait for it to finish.", told.getLast());
        fixture.settle();
        assertEquals(1, fixture.backend.writes.get());
    }

    @Test
    void anOlderDialogIsStaleAndCannotChangeAnything() {
        start(true);
        Player alice = chest.player(ALICE);
        dialog.open(alice);
        var stale = fake.last();
        dialog.open(alice);
        press(stale, alice, TextureDialog.Button.DETAILED);
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
        assertEquals(1, fake.closes.get(ALICE));
        assertEquals("That menu is out of date. Reopen it with /ninjaarena textures.", told.getLast());
        press(fake.last(), alice, TextureDialog.Button.STANDARD);
        fixture.settle();
        assertEquals(1, fixture.backend.writes.get());
    }

    @Test
    void aPressAfterTheDialogWasForgottenIsStale() {
        start(true);
        Player alice = chest.player(ALICE);
        dialog.open(alice);
        var shown = fake.last();
        dialog.forget(ALICE);
        press(shown, alice, TextureDialog.Button.DETAILED);
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
        assertTrue(told.getLast().contains("out of date"));
    }

    @Test
    void aDialogOwnedByAnotherPlayerIsRefused() {
        start(true);
        dialog.open(chest.player(ALICE));
        var alices = fake.last();
        press(alices, chest.player(BOB), TextureDialog.Button.DETAILED);
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
        assertEquals(1, fake.closes.get(BOB));
        assertTrue(told.getLast().contains("out of date"));
    }

    @Test
    void permissionIsRecheckedOnEveryPressAndOnOpen() {
        start(true);
        Player alice = chest.player(ALICE);
        dialog.open(alice);
        var shown = fake.last();
        chest.revokePermission(ALICE);
        press(shown, alice, TextureDialog.Button.DETAILED);
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
        assertEquals(1, fake.closes.get(ALICE));
        assertTrue(told.getLast().contains("permission"));
        int shows = fake.shown.size();
        dialog.open(alice);
        assertEquals(shows, fake.shown.size());
        assertTrue(told.getLast().contains("permission"));
        press(shown, alice, TextureDialog.Button.STANDARD);
        assertTrue(told.getLast().contains("out of date"));
    }

    @Test
    void closeEndsTheDialogAndLaterPressesAreStale() {
        start(true);
        Player alice = chest.player(ALICE);
        dialog.open(alice);
        var shown = fake.last();
        press(shown, alice, TextureDialog.Button.CLOSE);
        assertEquals(1, fake.closes.get(ALICE));
        press(shown, alice, TextureDialog.Button.DETAILED);
        assertTrue(fixture.backend.rows.isEmpty());
        assertTrue(told.getLast().contains("out of date"));
    }

    @Test
    void anOpenThatThrowsLeavesNothingCurrent() {
        start(true);
        Player alice = chest.player(ALICE);
        dialog.open(alice);
        var shown = fake.last();
        fake.failShow = new IllegalStateException("boom");
        try {
            dialog.open(alice);
        } catch (IllegalStateException expected) {
            // The caller falls back to the chest menu.
        }
        fake.failShow = null;
        press(shown, alice, TextureDialog.Button.DETAILED);
        assertTrue(fixture.backend.rows.isEmpty());
        assertTrue(told.getLast().contains("out of date"));
    }

    @Test
    void whenTheDialogPathThrowsTheChestOpensAndTheFailureIsLoggedOnce() {
        start(true);
        fake.failShow = new IllegalStateException("no dialogs");
        List<String> log = new ArrayList<>();
        var menus = new TextureMenus(TextureMenuMode.DIALOG, chest.menu, dialog, log::add);
        assertEquals("dialog", menus.describe());
        menus.open(chest.player(ALICE));
        menus.open(chest.player(BOB));
        assertEquals(2, chest.opened.size());
        assertEquals(1, log.size());
        assertTrue(log.getFirst().contains("no dialogs"));
        assertEquals("dialog (chest after an error)", menus.describe());
        assertTrue(fake.shown.isEmpty());
    }

    @Test
    void chestModeNeverTouchesTheDialogAndDialogModeNeverOpensTheChest() {
        start(true);
        var chestMenus = new TextureMenus(TextureMenuMode.CHEST, chest.menu, dialog, message -> { });
        chestMenus.open(chest.player(ALICE));
        assertEquals(1, chest.opened.size());
        assertTrue(fake.shown.isEmpty());
        assertEquals("chest", chestMenus.describe());
        var dialogMenus = new TextureMenus(TextureMenuMode.DIALOG, chest.menu, dialog, message -> { });
        dialogMenus.open(chest.player(BOB));
        assertEquals(1, chest.opened.size());
        assertEquals(1, fake.shown.size());
    }

    @Test
    void bothMenusRunTheSameServicePathForSavedAndSessionOnlyChoices() {
        for (boolean authenticated : new boolean[] {true, false}) {
            for (Quality quality : Quality.values()) {
                int slot = quality == Quality.DETAILED ? TextureMenu.DETAILED_SLOT : TextureMenu.STANDARD_SLOT;
                var button = quality == Quality.DETAILED ? TextureDialog.Button.DETAILED
                        : TextureDialog.Button.STANDARD;

                start(authenticated);
                chest.menu.open(chest.player(ALICE));
                chest.menu.click(chest.player(ALICE), chest.screen().holder, slot);
                fixture.settle();
                String viaChest = fixture.describeAll(ALICE);
                List<String> chestTold = List.copyOf(chest.menuTold);
                int chestWrites = fixture.backend.writes.get();
                close();

                told.clear();
                start(authenticated);
                dialog.open(chest.player(ALICE));
                press(fake.last(), chest.player(ALICE), button);
                fixture.settle();
                assertEquals(viaChest, fixture.describeAll(ALICE));
                assertEquals(chestTold, told);
                assertEquals(chestWrites, fixture.backend.writes.get());
                close();
                told.clear();
            }
        }
    }

    @Test
    void closeAllClosesEveryOpenDialog() {
        start(true);
        dialog.open(chest.player(ALICE));
        dialog.open(chest.player(BOB));
        dialog.closeAll(id -> id.equals(ALICE) ? chest.player(ALICE) : null);
        assertEquals(1, fake.closes.get(ALICE));
        assertFalse(fake.closes.containsKey(BOB));
        press(fake.shown.getFirst(), chest.player(ALICE), TextureDialog.Button.DETAILED);
        assertTrue(told.getLast().contains("out of date"));
    }

    @Test
    void aDialogIsRefusedInAMatchWithTheChestMessageAndLeavesNothingCurrent() {
        start(true);
        inMatch.add(ALICE);
        dialog.open(chest.player(ALICE));
        assertTrue(fake.shown.isEmpty());
        assertEquals(TextureMenu.LOCKED, told.getLast());
        assertEquals("The texture menu cannot be opened right now. Leave your match and try again.", told.getLast());
        inMatch.clear();
        dialog.open(chest.player(ALICE));
        assertEquals(1, fake.shown.size());
    }

    @Test
    void aPressFromAnOpenDialogIsRefusedOnceThePlayerIsInAMatch() {
        start(true);
        Player alice = chest.player(ALICE);
        dialog.open(alice);
        var shown = fake.last();
        inMatch.add(ALICE);
        press(shown, alice, TextureDialog.Button.DETAILED);
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
        assertEquals(0, fixture.backend.writes.get());
        assertEquals(1, fake.closes.get(ALICE));
        assertEquals(TextureMenu.LOCKED, told.getLast());
        inMatch.clear();
        press(shown, alice, TextureDialog.Button.DETAILED);
        assertTrue(told.getLast().contains("out of date"));
    }
}
