package com.ninja6.ninjaarena.texture;

import static com.ninja6.ninjaarena.texture.TextureFixture.ALICE;
import static com.ninja6.ninjaarena.texture.TextureFixture.BOB;
import static com.ninja6.ninjaarena.texture.TextureFixture.FLOOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import com.ninja6.ninjaarena.texture.TextureMenu.Item;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TextureMenuTest {

    private TextureFixture fixture;
    private MenuHarness harness;

    private MenuHarness harness(boolean detailedConfigured) {
        fixture = new TextureFixture(true, detailedConfigured);
        harness = new MenuHarness(fixture);
        return harness;
    }

    @AfterEach
    void close() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private void joined(java.util.UUID id) {
        fixture.join(id);
        fixture.loadBase(id);
        fixture.settle();
    }

    @Test
    void theMenuUsesVanillaIconsAndPlainTextWithTheFourControls() {
        MenuHarness h = harness(true);
        joined(ALICE);
        h.menu.open(h.player(ALICE));
        var screen = h.screen();
        assertEquals(1, h.opened.size());
        assertEquals(Set.of(10, 12, 14, 16), screen.items.keySet());
        assertTrue(screen.items.get(10).startsWith("Standard 16x16|"));
        assertTrue(screen.items.get(12).startsWith("Detailed 32x32|"));
        assertTrue(screen.items.get(14).startsWith("Status|"));
        assertTrue(screen.items.get(16).startsWith("Close|"));
        assertEquals(Map.of(10, Material.PAPER, 12, Material.DIAMOND, 14, Material.BOOK, 16, Material.OAK_DOOR),
                screen.icons);
        assertEquals("Arena Texture Quality", TextureMenu.TITLE);
        assertEquals(27, TextureMenu.SIZE);
    }

    @Test
    void theSelectedChoiceIsShownByTextAndColourAndANewPlayerSeesStandardSelected() {
        MenuHarness h = harness(true);
        joined(ALICE);
        h.menu.open(h.player(ALICE));
        assertTrue(h.screen().items.get(10).contains("[SELECTED]"));
        assertFalse(h.screen().items.get(12).contains("[SELECTED]"));
        assertTrue(h.screen().items.get(14).contains("Saved choice: Standard (default)"));

        var standard = TextureMenu.layout(fixture.service.describe(ALICE), null, "");
        Item selected = standard.get(10);
        Item other = standard.get(12);
        assertEquals(NamedTextColor.GREEN, selected.color());
        assertNotEquals(selected.color(), other.color());
        assertTrue(selected.glint());
        assertTrue(selected.lore().stream().anyMatch(line -> line.startsWith("[SELECTED]")));
    }

    @Test
    void choosingDetailedShowsSavingUntilTheWriteCommitsThenSelectsIt() {
        MenuHarness h = harness(true);
        joined(ALICE);
        h.menu.open(h.player(ALICE));
        var screen = h.screen();
        h.menu.click(h.player(ALICE), screen.holder, TextureMenu.DETAILED_SLOT);
        assertTrue(screen.items.get(12).contains("Saving..."));
        assertFalse(screen.items.get(12).contains("[SELECTED]"));
        fixture.settle();
        assertTrue(screen.items.get(12).contains("[SELECTED]"));
        assertFalse(screen.items.get(12).contains("Saving..."));
        assertFalse(screen.items.get(10).contains("[SELECTED]"));
        assertTrue(screen.items.get(14).contains("Saved: Detailed."));
        assertTrue(h.menuTold.getLast().startsWith("Saved: Detailed."));
        assertEquals(Quality.DETAILED, fixture.backend.rows.get(PlayerKey.java(ALICE)).quality());
        assertEquals(1, fixture.wire.overlayOffers());
    }

    @Test
    void aFailedWriteNeverAppearsSaved() {
        MenuHarness h = harness(true);
        joined(ALICE);
        fixture.backend.failWrites = true;
        h.menu.open(h.player(ALICE));
        var screen = h.screen();
        h.menu.click(h.player(ALICE), screen.holder, TextureMenu.DETAILED_SLOT);
        fixture.settle();
        assertFalse(screen.items.get(12).contains("[SELECTED]"));
        assertFalse(screen.items.get(12).contains("Saving..."));
        assertTrue(screen.items.get(10).contains("[SELECTED]"));
        assertTrue(screen.items.get(14).contains("Could not save"));
        assertFalse(h.menuTold.getLast().contains("Saved:"));
        assertTrue(fixture.backend.rows.isEmpty());
        assertEquals(0, fixture.wire.overlayOffers());
    }

    @Test
    void aSecondClickWhileSavingIsIgnored() {
        MenuHarness h = harness(true);
        joined(ALICE);
        h.menu.open(h.player(ALICE));
        var holder = h.screen().holder;
        h.menu.click(h.player(ALICE), holder, TextureMenu.DETAILED_SLOT);
        h.menu.click(h.player(ALICE), holder, TextureMenu.STANDARD_SLOT);
        assertEquals("Still saving your last choice. Wait for it to finish.", h.menuTold.getLast());
        fixture.settle();
        assertEquals(1, fixture.backend.writes.get());
        assertEquals(Quality.DETAILED, fixture.backend.rows.get(PlayerKey.java(ALICE)).quality());
    }

    @Test
    void aMenuThatIsNoLongerTheCurrentOneCannotChangeAnything() {
        MenuHarness h = harness(true);
        joined(ALICE);
        h.menu.open(h.player(ALICE));
        var stale = h.screen().holder;
        h.menu.open(h.player(ALICE));
        var current = h.screen().holder;
        assertNotEquals(stale.token(), current.token());
        assertFalse(h.menu.isCurrent(ALICE, stale));
        assertTrue(h.menu.isCurrent(ALICE, current));
        h.menu.click(h.player(ALICE), stale, TextureMenu.DETAILED_SLOT);
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
        assertEquals(1, h.closes.get(ALICE));
        assertTrue(h.menuTold.getLast().contains("out of date"));
        h.menu.closed(ALICE, stale);
        assertTrue(h.menu.isCurrent(ALICE, current));
    }

    @Test
    void aMenuOwnedByAnotherPlayerIsRefused() {
        MenuHarness h = harness(true);
        joined(ALICE);
        joined(BOB);
        h.menu.open(h.player(ALICE));
        var alices = h.screen().holder;
        h.menu.click(h.player(BOB), alices, TextureMenu.DETAILED_SLOT);
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
        assertEquals(1, h.closes.get(BOB));
    }

    @Test
    void permissionIsRecheckedOnEveryChoiceAndOnOpen() {
        MenuHarness h = harness(true);
        joined(ALICE);
        h.menu.open(h.player(ALICE));
        var holder = h.screen().holder;
        h.revokePermission(ALICE);
        h.menu.click(h.player(ALICE), holder, TextureMenu.DETAILED_SLOT);
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
        assertEquals(1, h.closes.get(ALICE));
        assertTrue(h.menuTold.getLast().contains("permission"));
        int opens = h.opened.size();
        h.menu.open(h.player(ALICE));
        assertEquals(opens, h.opened.size());
    }

    @Test
    void statusRefreshesAndCloseClosesAndOtherSlotsDoNothing() {
        MenuHarness h = harness(true);
        joined(ALICE);
        h.menu.open(h.player(ALICE));
        var screen = h.screen();
        int sets = screen.sets;
        h.menu.click(h.player(ALICE), screen.holder, TextureMenu.STATUS_SLOT);
        assertTrue(screen.sets > sets);
        sets = screen.sets;
        h.menu.click(h.player(ALICE), screen.holder, 0);
        h.menu.click(h.player(ALICE), screen.holder, 26);
        assertEquals(sets, screen.sets);
        h.menu.click(h.player(ALICE), screen.holder, TextureMenu.CLOSE_SLOT);
        assertEquals(1, h.closes.get(ALICE));
        assertTrue(fixture.backend.rows.isEmpty());
    }

    @Test
    void anUnconfiguredDetailedIsVisiblyUnavailableAndCannotBeChosen() {
        MenuHarness h = harness(false);
        joined(ALICE);
        h.menu.open(h.player(ALICE));
        var screen = h.screen();
        assertTrue(screen.items.get(12).contains("unavailable"));
        assertTrue(screen.items.get(12).contains("not set up"));
        assertEquals(Material.BARRIER, screen.icons.get(12));
        h.menu.click(h.player(ALICE), screen.holder, TextureMenu.DETAILED_SLOT);
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
        assertTrue(h.menuTold.getLast().contains("not set up"));
    }

    @Test
    void aSavedDetailedThatIsNoLongerConfiguredIsNotMistakenForStandard() {
        MenuHarness h = harness(false);
        fixture.saved(BOB, Quality.DETAILED);
        joined(BOB);
        h.menu.open(h.player(BOB));
        var screen = h.screen();
        assertTrue(screen.items.get(12).contains("[SELECTED]"));
        assertTrue(screen.items.get(12).contains("Standard is used until it is set up"));
        assertFalse(screen.items.get(10).contains("[SELECTED]"));
        assertTrue(screen.items.get(14).contains("Saved choice: Detailed"));
        assertTrue(screen.items.get(14).contains("Detailed is saved but not set up"));
    }

    @Test
    void theStatusSeparatesTheSavedChoiceFromTheCurrentDelivery() {
        MenuHarness h = harness(true);
        joined(ALICE);
        h.menu.open(h.player(ALICE));
        var screen = h.screen();
        h.menu.click(h.player(ALICE), screen.holder, TextureMenu.DETAILED_SLOT);
        fixture.settle();
        fixture.overlayStatus(ALICE, org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DECLINED);
        h.menu.click(h.player(ALICE), screen.holder, TextureMenu.STATUS_SLOT);
        String status = screen.items.get(14);
        assertTrue(status.contains("Saved choice: Detailed"), status);
        assertTrue(status.contains("Right now: Standard is active; Detailed was declined"), status);
    }

    @Test
    void bedrockMenuSaysThatTheChoiceAppliesOnTheNextConnection() {
        MenuHarness h = harness(true);
        fixture.bedrockPlayers.add(FLOOD);
        fixture.xuids.put(FLOOD, "2535400000001");
        fixture.bedrockActive.put(FLOOD, Quality.STANDARD);
        fixture.join(FLOOD);
        fixture.settle();
        h.menu.open(h.player(FLOOD));
        var screen = h.screen();
        assertTrue(screen.items.get(12).contains("Applies on your next connection."));
        h.menu.click(h.player(FLOOD), screen.holder, TextureMenu.DETAILED_SLOT);
        fixture.settle();
        assertEquals("Saved: Detailed. Applies on your next connection. Pack loading cannot be observed.",
                h.menuTold.getLast());
        assertTrue(screen.items.get(14).contains("this connection was given Standard"));
        assertTrue(fixture.wire.sent.isEmpty());
    }

    @Test
    void closingTheMenuWhileSavingStillReportsInChatButNeverRedraws() {
        MenuHarness h = harness(true);
        joined(ALICE);
        h.menu.open(h.player(ALICE));
        var screen = h.screen();
        h.menu.click(h.player(ALICE), screen.holder, TextureMenu.DETAILED_SLOT);
        h.menu.closed(ALICE, screen.holder);
        int sets = screen.sets;
        fixture.settle();
        assertEquals(sets, screen.sets);
        assertTrue(h.menuTold.getLast().startsWith("Saved: Detailed."));
    }

    @Test
    void aWindowThatAnotherRuleRefusesIsReportedNotLeftHalfOpen() {
        MenuHarness h = harness(true);
        joined(ALICE);
        h.refuseOpen = true;
        h.menu.open(h.player(ALICE));
        assertTrue(h.menuTold.getLast().contains("cannot be opened right now"));
        assertFalse(h.menu.isCurrent(ALICE, h.screen().holder));
    }

    @Test
    void wrappingKeepsTooltipLinesShort() {
        assertEquals(java.util.List.of("one two", "three"), TextureMenu.wrap("one two three", 8));
        assertEquals(java.util.List.of("short"), TextureMenu.wrap("short", 44));
    }
}
