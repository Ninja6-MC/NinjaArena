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

    private final java.util.Set<UUID> spectating = new java.util.HashSet<>();

    private boolean locked(UUID id) {
        return inMatch.contains(id) || spectating.contains(id);
    }

    private void start(boolean authenticated) {
        fixture = new TextureFixture(true, true);
        fixture.authenticated = authenticated;
        chest = new MenuHarness(fixture, this::locked);
        fake = new Fake();
        dialog = new TextureDialog(fixture.service, fake, (id, message) -> told.add(message), this::locked);
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
    void bothModesOpenAndChestChoicesWorkThroughTheLobbyLockdown() {
        start(true);
        var lockdown = new com.ninja6.ninjaarena.kit.InventoryLockdownListener(id -> true);
        for (TextureMenuMode mode : TextureMenuMode.values()) {
            var menus = new TextureMenus(mode, chest.menu, dialog, message -> { });
            menus.open(chest.player(ALICE));
            menus.open(chest.player(BOB));
        }
        assertEquals(2, fake.shown.size());
        assertEquals(2, chest.opened.size());
        for (var opened : chest.opened) {
            var view = chest.view(opened.player(), opened.inventory());
            var open = new org.bukkit.event.inventory.InventoryOpenEvent(view);
            lockdown.onOpen(open);
            assertFalse(open.isCancelled());
            var click = new org.bukkit.event.inventory.InventoryClickEvent(view,
                    org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, TextureMenu.DETAILED_SLOT,
                    org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
            lockdown.onClick(click);
            assertFalse(click.isCancelled());
            new TextureListener(fixture.service, chest.menu, dialog).onClick(click);
            assertTrue(click.isCancelled());
        }
        fixture.settle();
        assertEquals(2, fixture.backend.writes.get());
    }

    @Test
    void bothModesRefuseMatchPlayersAndSpectatorsWithTheReason() {
        start(true);
        inMatch.add(ALICE);
        spectating.add(BOB);
        for (TextureMenuMode mode : TextureMenuMode.values()) {
            var menus = new TextureMenus(mode, chest.menu, dialog, message -> { });
            menus.open(chest.player(ALICE));
            menus.open(chest.player(BOB));
        }
        assertTrue(fake.shown.isEmpty());
        assertTrue(chest.opened.isEmpty());
        String reason = "You can't change textures during a match or while spectating.";
        assertEquals(List.of(reason, reason), told);
        assertEquals(List.of(reason, reason), chest.menuTold);
    }

    @Test
    void aChestChoiceIsRefusedIfThePlayerStartsSpectatingAfterOpening() {
        start(true);
        chest.menu.open(chest.player(ALICE));
        var holder = chest.screen().holder;
        spectating.add(ALICE);
        chest.menu.click(chest.player(ALICE), holder, TextureMenu.DETAILED_SLOT);
        fixture.settle();
        assertEquals(0, fixture.backend.writes.get());
        assertFalse(chest.menu.isCurrent(ALICE, holder));
        assertEquals(1, chest.closes.get(ALICE));
        assertEquals("You can't change textures during a match or while spectating.", chest.menuTold.getLast());
    }

    @Test
    void anUnsavedDefaultHasThreeShortLinesAndUnmarkedButtons() {
        start(true);
        dialog.open(chest.player(ALICE));
        var content = fake.last().content();
        assertEquals("Arena Texture Quality", content.title());
        assertEquals("Standard 16x16", content.standard());
        assertEquals("Detailed 32x32", content.detailed());
        assertEquals(List.of("Your choice: Standard (not saved)", "Loaded now: Standard.",
                "Loads now, or when you rejoin if needed."), content.lines());
        assertCompact(content);
    }

    private void assertCompact(TextureDialog.Content content) {
        assertTrue(content.title().length() < 55);
        assertTrue(content.standard().length() < 40);
        assertTrue(content.detailed().length() < 40);
        assertTrue(com.ninja6.ninjaarena.message.Messages.text("texture.menu.close").length() < 40);
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
        assertEquals("Your choice: Standard", fake.last().content().lines().getFirst());
        assertEquals("Standard 16x16 (saved)", fake.last().content().standard());
        assertEquals("Detailed 32x32", fake.last().content().detailed());
        assertCompact(fake.last().content());
    }

    @Test
    void aSessionOnlyChoiceNeverClaimsToBeSaved() {
        start(false);
        fixture.choose(ALICE, Quality.DETAILED);
        dialog.open(chest.player(ALICE));
        assertEquals(List.of("This connection only (not saved): Detailed",
                "Loaded now: Standard; Detailed loading.", "For this connection; resets on leave."),
                fake.last().content().lines());
        assertCompact(fake.last().content());
    }

    private Player bedrockPlayer() {
        fixture.bedrockPlayers.add(FLOOD);
        fixture.xuids.put(FLOOD, "2535400000001");
        fixture.bedrockActive.put(FLOOD, Quality.STANDARD);
        joined(FLOOD);
        return chest.player(FLOOD);
    }

    @Test
    void aBedrockChoiceClosesOnceWithoutShowingSavingOrTheResultAndCanBeReopened() {
        for (Quality quality : Quality.values()) {
            start(true);
            Player player = bedrockPlayer();
            dialog.open(player);
            assertEquals(List.of("Your choice: Standard (not saved)", "Sent on join: Standard",
                    "A new choice loads when you rejoin."), fake.last().content().lines());
            press(fake.last(), player, quality == Quality.STANDARD ? TextureDialog.Button.STANDARD
                    : TextureDialog.Button.DETAILED);
            assertEquals(1, fake.closes.get(FLOOD));
            assertEquals(1, fake.shown.size());
            assertTrue(told.isEmpty());
            fixture.settle();
            assertEquals(1, fake.closes.get(FLOOD));
            assertEquals(1, fake.shown.size());
            assertEquals(1, told.size());
            assertTrue(told.getFirst().startsWith("Saved: " + TextureService.display(quality) + "."));
            dialog.closeAll(id -> player);
            assertEquals(1, fake.closes.get(FLOOD));
            dialog.open(player);
            assertEquals(2, fake.shown.size());
            assertEquals("Your choice: " + TextureService.display(quality),
                    fake.last().content().lines().getFirst());
            assertEquals(quality == Quality.STANDARD ? "Standard 16x16 (saved)" : "Standard 16x16",
                    fake.last().content().standard());
            assertEquals(quality == Quality.DETAILED ? "Detailed 32x32 (saved)" : "Detailed 32x32",
                    fake.last().content().detailed());
            assertCompact(fake.last().content());
            close();
            told.clear();
        }
    }

    @Test
    void aBedrockSaveFailureGoesToChatWithoutReopeningTheForm() {
        start(true);
        Player player = bedrockPlayer();
        fixture.backend.failWrites = true;
        dialog.open(player);
        press(fake.last(), player, TextureDialog.Button.DETAILED);
        assertEquals(1, fake.shown.size());
        assertEquals(1, fake.closes.get(FLOOD));
        fixture.settle();
        assertEquals(1, fake.shown.size());
        assertEquals(1, fake.closes.get(FLOOD));
        assertEquals(List.of(com.ninja6.ninjaarena.message.Messages.text("texture.save-failed")), told);
    }

    @Test
    void aBedrockSaveCallbackDoesNotUpdateAFormOpenedWhileItWasSaving() {
        start(true);
        Player player = bedrockPlayer();
        dialog.open(player);
        press(fake.last(), player, TextureDialog.Button.DETAILED);
        dialog.open(player);
        assertEquals(2, fake.shown.size());
        fixture.settle();
        assertEquals(2, fake.shown.size());
        assertEquals(1, fake.closes.get(FLOOD));
        assertEquals(1, told.size());
    }

    @Test
    void aFailedSaveStaysCompactAndDoesNotClaimThatTheDefaultWasSaved() {
        start(true);
        fixture.backend.failWrites = true;
        dialog.open(chest.player(ALICE));
        press(fake.last(), chest.player(ALICE), TextureDialog.Button.DETAILED);
        fixture.settle();
        assertEquals("Your choice: Standard (not saved)", fake.last().content().lines().getFirst());
        assertEquals("Could not save. Try again later.", fake.last().content().lines().get(1));
        assertCompact(fake.last().content());
    }

    private static TextureService.View storageView(String storage) {
        return new TextureService.View(java.util.Optional.of(PlayerKey.java(ALICE)), false,
                java.util.Optional.empty(), Quality.STANDARD, true, "Standard", storage, false, "Loaded now: Standard.",
                storage.startsWith("Storage is unsupported"));
    }

    @Test
    void aStorageOutageSaysToTryAgainLater() {
        var content = TextureDialog.content(storageView(
                "Storage is unavailable; Standard is used and choices cannot be saved."), null, "");
        assertEquals("Storage unavailable. Try again later.", content.lines().get(1));
        assertCompact(content);
    }

    @Test
    void unsupportedStorageDoesNotSuggestRetrying() {
        var content = TextureDialog.content(storageView(
                "Storage is unsupported (schema version 99); Standard is used."), null, "");
        assertEquals("Storage unsupported. Ask a server admin.", content.lines().get(1));
        assertFalse(String.join(" ", content.lines()).contains("Try again"));
        assertCompact(content);
    }

    @Test
    void anUnverifiedBedrockAccountSaysChoicesAreUnavailableAndPromisesNoSave() {
        start(true);
        fixture.bedrockPlayers.add(FLOOD);
        joined(FLOOD);
        dialog.open(chest.player(FLOOD));
        assertEquals(List.of("Your choice: Standard (not saved)", "Account unverified; choices unavailable.",
                "Standard is used; nothing is saved."), fake.last().content().lines());
        assertCompact(fake.last().content());
        press(fake.last(), chest.player(FLOOD), TextureDialog.Button.DETAILED);
        fixture.settle();
        assertEquals(1, fake.shown.size());
        assertEquals(1, fake.closes.get(FLOOD));
        assertEquals(List.of(com.ninja6.ninjaarena.message.Messages.text("texture.bedrock-unverified")), told);
        assertEquals(List.of("Your choice: Standard (not saved)", "Account unverified; choices unavailable.",
                "Standard is used; nothing is saved."), fake.last().content().lines());
    }

    @Test
    void aBedrockUnavailableChoiceClosesAndReportsTheReasonWithoutAnotherForm() {
        start(true);
        fixture.bedrockDetailed = false;
        Player player = bedrockPlayer();
        dialog.open(player);
        assertEquals("Detailed 32x32 (unavailable)", fake.last().content().detailed());
        press(fake.last(), player, TextureDialog.Button.DETAILED);
        fixture.settle();
        assertEquals(1, fake.shown.size());
        assertEquals(1, fake.closes.get(FLOOD));
        assertEquals(List.of(com.ninja6.ninjaarena.message.Messages.text("texture.detailed-unavailable")), told);
        assertEquals(0, fixture.backend.writes.get());
    }

    @Test
    void aSavedDetailedChoiceOnAServerWithoutDetailedSaysItIsNotSetUp() {
        fixture = new TextureFixture(true, false);
        chest = new MenuHarness(fixture, this::locked);
        fake = new Fake();
        dialog = new TextureDialog(fixture.service, fake, (id, message) -> told.add(message), this::locked);
        fixture.saved(ALICE, Quality.DETAILED);
        joined(ALICE);
        dialog.open(chest.player(ALICE));
        var content = fake.last().content();
        assertEquals(List.of("Your choice: Detailed", "Detailed is not set up on this server.",
                "Standard is used."), content.lines());
        assertEquals("Detailed 32x32 (unavailable) (saved)", content.detailed());
        assertCompact(content);
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
        assertTrue(text.contains("Your choice: Detailed"));
        assertTrue(told.getLast().startsWith("Saved: Detailed."));
        assertEquals(3, fake.shown.size());
        assertTrue(fake.closes.isEmpty());
        assertEquals("Detailed 32x32 (saved)", fake.last().content().detailed());
        assertCompact(fake.last().content());
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
        assertEquals(com.ninja6.ninjaarena.message.Messages.text("texture.locked"), told.getLast());
        assertEquals("You can't change textures during a match or while spectating.", told.getLast());
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
        assertEquals(com.ninja6.ninjaarena.message.Messages.text("texture.locked"), told.getLast());
        inMatch.clear();
        press(shown, alice, TextureDialog.Button.DETAILED);
        assertTrue(told.getLast().contains("out of date"));
    }
}
