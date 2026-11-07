package com.ninja6.ninjaarena.texture;

import static com.ninja6.ninjaarena.texture.TextureFixture.ALICE;
import static com.ninja6.ninjaarena.texture.TextureFixture.BASE;
import static com.ninja6.ninjaarena.texture.TextureFixture.BOB;
import static com.ninja6.ninjaarena.texture.TextureFixture.DETAIL;
import static com.ninja6.ninjaarena.texture.TextureFixture.FLOOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import com.ninja6.ninjaarena.resourcepack.ResourcePacks.OverlayState;
import com.ninja6.ninjaarena.texture.TextureFixture.Removed;
import com.ninja6.ninjaarena.texture.TextureFixture.Sent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class TextureServiceTest {

    private TextureFixture fixture;

    private TextureFixture fixture(boolean required) {
        fixture = new TextureFixture(required, true);
        return fixture;
    }

    @AfterEach
    void close() {
        if (fixture != null) {
            fixture.close();
        }
    }

    // Defaults and explicit choice.

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void newPlayersGetStandardAndNeverAnOverlay(boolean required) {
        TextureFixture f = fixture(required);
        f.join(ALICE);
        f.settle();
        f.loadBase(ALICE);
        f.settle();
        assertEquals(0, f.wire.overlayOffers());
        var view = f.service.describe(ALICE);
        assertEquals(Quality.STANDARD, view.saved());
        assertTrue(view.explicit().isEmpty());
        assertTrue(f.backend.rows.isEmpty());
        assertEquals(1, f.wire.sent.size());
        assertEquals(required, f.wire.sent.getFirst().required());
    }

    // Java sequencing.

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSavedDetailedWaitsForTheBaseToLoadThenAsksOnceAndOptionally(boolean required) {
        TextureFixture f = fixture(required);
        f.saved(ALICE, Quality.DETAILED);
        f.join(ALICE);
        f.settle();
        assertEquals(List.of(new Sent(ALICE, BASE, required)), f.wire.sent);
        f.packs.status(ALICE, BASE.id(), Status.ACCEPTED);
        f.packs.status(ALICE, BASE.id(), Status.DOWNLOADED);
        f.settle();
        assertEquals(0, f.wire.overlayOffers());
        assertEquals(OverlayState.NONE, f.packs.overlay(ALICE));
        f.loadBase(ALICE);
        f.loadBase(ALICE);
        f.settle();
        assertEquals(new Sent(ALICE, DETAIL, false), f.wire.sent.getLast());
        assertEquals(1, f.wire.overlayOffers());
        assertEquals(OverlayState.PENDING, f.packs.overlay(ALICE));
        assertTrue(f.service.describe(ALICE).delivery().contains("waiting for your client"));
        f.overlayStatus(ALICE, Status.SUCCESSFULLY_LOADED);
        assertEquals(OverlayState.LOADED, f.packs.overlay(ALICE));
        assertEquals("Detailed is active", f.service.describe(ALICE).delivery());
        assertTrue(f.wire.kicks.isEmpty());
    }

    @Test
    void theBaseMayLoadBeforeThePreferenceArrives() throws Exception {
        TextureFixture f = fixture(true);
        f.saved(ALICE, Quality.DETAILED);
        CountDownLatch gate = new CountDownLatch(1);
        f.backend.gate = gate;
        f.join(ALICE);
        f.loadBase(ALICE);
        assertEquals(0, f.wire.overlayOffers());
        f.backend.gate = null;
        gate.countDown();
        f.settle();
        assertEquals(1, f.wire.overlayOffers());
    }

    @Test
    void theJoinOrderBetweenPackAuthorityAndPreferencesDoesNotMatter() {
        TextureFixture f = fixture(true);
        f.saved(ALICE, Quality.DETAILED);
        f.service.join(ALICE);
        f.settle();
        f.packs.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        assertEquals(1, f.wire.overlayOffers());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"DECLINED", "FAILED_DOWNLOAD", "INVALID_URL", "FAILED_RELOAD", "DISCARDED"})
    void aDeclinedOrFailedOverlayKeepsTheBaseTheChoiceAndTheConnection(Status failure) {
        TextureFixture f = fixture(true);
        f.saved(ALICE, Quality.DETAILED);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        f.overlayStatus(ALICE, Status.ACCEPTED);
        f.overlayStatus(ALICE, failure);
        f.overlayStatus(ALICE, Status.SUCCESSFULLY_LOADED);
        f.loadBase(ALICE);
        f.settle();
        assertTrue(f.packs.loaded(ALICE));
        assertTrue(f.wire.kicks.isEmpty());
        assertEquals(List.of(new Removed(ALICE, DETAIL.id())), f.wire.removed);
        assertEquals(1, f.wire.overlayOffers());
        assertEquals(Quality.DETAILED, f.service.describe(ALICE).saved());
        OverlayState expected = failure == Status.DECLINED ? OverlayState.DECLINED : OverlayState.FAILED;
        assertEquals(expected, f.packs.overlay(ALICE));
        assertTrue(f.told.getLast().contains("Standard stays active"), f.told.getLast());
        assertTrue(f.told.getLast().contains("kept"));
        assertTrue(f.service.describe(ALICE).delivery().startsWith("Standard is active; Detailed "));
    }

    @Test
    void anOverlayTimeoutOrSendFailureAlsoFallsBackWithoutKicking() {
        TextureFixture f = fixture(true);
        f.saved(ALICE, Quality.DETAILED);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        f.ticks.set(2_400);
        f.packs.tick();
        assertEquals(OverlayState.FAILED, f.packs.overlay(ALICE));
        assertEquals("timed out while loading", f.packs.overlayReason(ALICE));
        assertTrue(f.packs.loaded(ALICE));
        assertTrue(f.wire.kicks.isEmpty());

        f.packs.quit(ALICE);
        f.service.quit(ALICE);
        f.wire.breakOverlaySend = true;
        f.wire.removed.clear();
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        assertEquals(OverlayState.FAILED, f.packs.overlay(ALICE));
        assertEquals("could not be requested", f.packs.overlayReason(ALICE));
        assertEquals(List.of(new Removed(ALICE, DETAIL.id())), f.wire.removed);
        assertTrue(f.packs.loaded(ALICE));
        assertTrue(f.wire.kicks.isEmpty());
        f.wire.breakOverlaySend = false;
        f.loadBase(ALICE);
        f.settle();
        assertEquals(OverlayState.FAILED, f.packs.overlay(ALICE));
    }

    @Test
    void anOverlayResultAfterTheConnectionEndedCannotReviveIt() {
        TextureFixture f = fixture(true);
        f.saved(ALICE, Quality.DETAILED);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        f.packs.quit(ALICE);
        f.service.quit(ALICE);
        f.overlayStatus(ALICE, Status.SUCCESSFULLY_LOADED);
        f.loadBase(ALICE);
        f.settle();
        assertEquals(OverlayState.NONE, f.packs.overlay(ALICE));
        assertEquals(1, f.wire.overlayOffers());
    }

    @Test
    void reconnectRestoresThroughTheSameSequenceAndNeverTrustsTheCache() {
        TextureFixture f = fixture(true);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        f.choose(ALICE, Quality.DETAILED);
        f.overlayStatus(ALICE, Status.SUCCESSFULLY_LOADED);
        f.packs.quit(ALICE);
        f.service.quit(ALICE);

        f.join(ALICE);
        f.settle();
        assertEquals(2, f.wire.sent.stream().filter(s -> s.pack().id().equals(BASE.id())).count());
        assertEquals(1, f.wire.overlayOffers());
        assertEquals(OverlayState.NONE, f.packs.overlay(ALICE));
        f.packs.status(ALICE, BASE.id(), Status.DOWNLOADED);
        f.settle();
        assertEquals(1, f.wire.overlayOffers());
        f.loadBase(ALICE);
        f.settle();
        assertEquals(2, f.wire.overlayOffers());
        assertEquals(OverlayState.PENDING, f.packs.overlay(ALICE));
    }

    @Test
    void aChangedOverlayHashIsRequestedAgainUnderTheSameIdentity() {
        TextureFixture f = fixture(true);
        f.saved(ALICE, Quality.DETAILED);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        assertEquals("b".repeat(40), f.wire.sent.getLast().pack().sha1());
        f.close();
        var updated = new com.ninja6.ninjaarena.resourcepack.ResourcePackSettings.Pack(DETAIL.id(),
                "https://example.org/detailed-v2.zip", "c".repeat(40));
        fixture = new TextureFixture(true, true, updated);
        fixture.saved(ALICE, Quality.DETAILED);
        fixture.join(ALICE);
        fixture.loadBase(ALICE);
        fixture.settle();
        assertEquals(new Sent(ALICE, updated, false), fixture.wire.sent.getLast());
    }

    @Test
    void anOverlayFailureOnOnePlayerDoesNotTouchAnother() {
        TextureFixture f = fixture(true);
        f.saved(ALICE, Quality.DETAILED);
        f.saved(BOB, Quality.DETAILED);
        f.join(ALICE);
        f.join(BOB);
        f.loadBase(ALICE);
        f.loadBase(BOB);
        f.settle();
        f.overlayStatus(ALICE, Status.FAILED_RELOAD);
        f.overlayStatus(BOB, Status.SUCCESSFULLY_LOADED);
        assertEquals(OverlayState.FAILED, f.packs.overlay(ALICE));
        assertEquals(OverlayState.LOADED, f.packs.overlay(BOB));
        assertEquals(List.of(new Removed(ALICE, DETAIL.id())), f.wire.removed);
    }

    // The Standard / Detailed / Standard cycle.

    @Test
    void choosingStandardBeforeTheBaseLoadsCancelsThePendingDetailedIntent() {
        TextureFixture f = fixture(true);
        f.saved(ALICE, Quality.DETAILED);
        f.join(ALICE);
        f.settle();
        var result = f.choose(ALICE, Quality.STANDARD);
        assertTrue(result.saved());
        f.loadBase(ALICE);
        f.settle();
        assertEquals(0, f.wire.overlayOffers());
        assertEquals(Quality.STANDARD, f.backend.rows.get(PlayerKey.java(ALICE)).quality());
        assertEquals(2, f.backend.rows.get(PlayerKey.java(ALICE)).revision());
        assertTrue(f.wire.removed.isEmpty());
    }

    @Test
    void theCycleIsImmediateRemovesOnlyTheOverlayAndStopsLateStatuses() {
        TextureFixture f = fixture(true);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        assertEquals("Saved: Detailed. Requesting the Detailed overlay now. If you decline it, Standard stays active.",
                f.choose(ALICE, Quality.DETAILED).message());
        f.overlayStatus(ALICE, Status.SUCCESSFULLY_LOADED);
        assertEquals(OverlayState.LOADED, f.packs.overlay(ALICE));

        var back = f.choose(ALICE, Quality.STANDARD);
        assertEquals("Saved: Standard. The Detailed overlay is removed for this connection.", back.message());
        assertEquals(List.of(new Removed(ALICE, DETAIL.id())), f.wire.removed);
        assertEquals(OverlayState.CANCELLED, f.packs.overlay(ALICE));
        assertTrue(f.packs.loaded(ALICE));
        assertEquals(Quality.STANDARD, f.service.describe(ALICE).saved());

        f.overlayStatus(ALICE, Status.SUCCESSFULLY_LOADED);
        f.overlayStatus(ALICE, Status.DOWNLOADED);
        f.loadBase(ALICE);
        f.settle();
        assertEquals(OverlayState.CANCELLED, f.packs.overlay(ALICE));
        assertEquals(1, f.wire.overlayOffers());
    }

    @Test
    void aSecondDetailedChoiceOnTheSameConnectionIsSavedForReconnect() {
        TextureFixture f = fixture(true);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        f.choose(ALICE, Quality.DETAILED);
        f.choose(ALICE, Quality.STANDARD);
        var again = f.choose(ALICE, Quality.DETAILED);
        assertTrue(again.saved());
        assertTrue(again.message().contains("already used its one overlay request"), again.message());
        assertTrue(again.message().contains("next connection"));
        assertEquals(1, f.wire.overlayOffers());
        assertEquals(Quality.DETAILED, f.service.describe(ALICE).saved());
        assertEquals(Quality.DETAILED, f.backend.rows.get(PlayerKey.java(ALICE)).quality());
        assertTrue(f.service.describe(ALICE).delivery().contains("removed for this connection"));
    }

    @Test
    void afterAFailureAnotherDetailedChoiceIsOnlySavedNotReoffered() {
        TextureFixture f = fixture(false);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        f.choose(ALICE, Quality.DETAILED);
        f.overlayStatus(ALICE, Status.FAILED_DOWNLOAD);
        var again = f.choose(ALICE, Quality.DETAILED);
        assertTrue(again.saved());
        assertEquals(1, f.wire.overlayOffers());
        assertEquals(OverlayState.FAILED, f.packs.overlay(ALICE));
    }

    @Test
    void detailedChosenBeforeTheBaseLoadsIsRequestedAfterItDoes() {
        TextureFixture f = fixture(true);
        f.join(ALICE);
        f.settle();
        var result = f.choose(ALICE, Quality.DETAILED);
        assertEquals("Saved: Detailed. It will be requested once Standard has loaded.", result.message());
        assertEquals(0, f.wire.overlayOffers());
        assertTrue(f.service.describe(ALICE).delivery().contains("waits for Standard to load"));
        f.loadBase(ALICE);
        f.settle();
        assertEquals(1, f.wire.overlayOffers());
    }

    @Test
    void aChoiceMadeAcrossAReconnectDoesNotLeakIntoTheNewConnectionTwice() {
        TextureFixture f = fixture(true);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        TextureService.Result[] result = new TextureService.Result[1];
        f.service.choose(ALICE, Quality.DETAILED, value -> result[0] = value);
        f.packs.quit(ALICE);
        f.service.quit(ALICE);
        f.join(ALICE);
        f.settle();
        assertTrue(result[0].saved());
        assertTrue(result[0].message().endsWith("Applies on your next connection."), result[0].message());
        f.loadBase(ALICE);
        f.settle();
        assertEquals(1, f.wire.overlayOffers());
    }

    @Test
    void aStaleLoadFromAPreviousConnectionIsIgnored() throws Exception {
        TextureFixture f = fixture(true);
        f.saved(ALICE, Quality.DETAILED);
        CountDownLatch gate = new CountDownLatch(1);
        f.backend.gate = gate;
        f.join(ALICE);
        while (f.backend.loads.get() < 1) {
            Thread.sleep(5);
        }
        f.packs.quit(ALICE);
        f.service.quit(ALICE);
        f.backend.rows.put(PlayerKey.java(ALICE), new Stored(Quality.STANDARD, 2, 2));
        f.backend.gate = null;
        f.join(ALICE);
        f.loadBase(ALICE);
        gate.countDown();
        f.settle();
        assertEquals(0, f.wire.overlayOffers());
        assertEquals(Quality.STANDARD, f.service.describe(ALICE).saved());
    }

    // Failed saves.

    @Test
    void aFailedWriteIsNeverReportedSavedAndChangesNothing() {
        TextureFixture f = fixture(true);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        f.backend.failWrites = true;
        var result = f.choose(ALICE, Quality.DETAILED);
        assertFalse(result.saved());
        assertEquals(TextureService.SAVE_FAILED, result.message());
        assertEquals(0, f.wire.overlayOffers());
        assertTrue(f.service.describe(ALICE).explicit().isEmpty());
        assertTrue(f.backend.rows.isEmpty());
    }

    @Test
    void aFailedStandardWriteStillStopsTheOverlayForThisConnectionButSaysSo() {
        TextureFixture f = fixture(true);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        f.choose(ALICE, Quality.DETAILED);
        f.backend.failWrites = true;
        var result = f.choose(ALICE, Quality.STANDARD);
        assertFalse(result.saved());
        assertTrue(result.message().contains("overlay was removed for this connection only"));
        assertEquals(OverlayState.CANCELLED, f.packs.overlay(ALICE));
        assertEquals(Quality.DETAILED, f.service.describe(ALICE).saved());
    }

    @Test
    void aJoinThatCannotReadStorageFallsBackToStandardAndSaysSo() {
        TextureFixture f = fixture(true);
        f.saved(ALICE, Quality.DETAILED);
        f.backend.failReads = true;
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        assertEquals(0, f.wire.overlayOffers());
        assertTrue(f.told.stream().anyMatch(line -> line.contains("could not be read")), f.told.toString());
    }

    // Detailed not configured.

    @Test
    void anUnconfiguredDetailedIsRefusedWhileASavedDetailedStaysDistinctFromStandard() {
        TextureFixture f = new TextureFixture(true, false);
        fixture = f;
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        var refused = f.choose(ALICE, Quality.DETAILED);
        assertFalse(refused.saved());
        assertTrue(refused.message().contains("not set up"));
        assertTrue(f.backend.rows.isEmpty());

        f.saved(BOB, Quality.DETAILED);
        f.join(BOB);
        f.loadBase(BOB);
        f.settle();
        var view = f.service.describe(BOB);
        assertFalse(view.detailedAvailable());
        assertEquals(Quality.DETAILED, view.saved());
        assertEquals(Quality.DETAILED, view.explicit().orElseThrow());
        assertEquals("Standard is active; Detailed is saved but not set up on this server", view.delivery());
        assertEquals(0, f.wire.overlayOffers());
    }

    // Identity.

    @Test
    void aMissingOrUntrustedIdentityGetsStandardAndCannotSave() {
        TextureFixture f = fixture(true);
        f.authenticated = false;
        f.saved(ALICE, Quality.DETAILED);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        assertEquals(0, f.wire.overlayOffers());
        var result = f.choose(ALICE, Quality.DETAILED);
        assertFalse(result.saved());
        assertTrue(result.message().contains("could not be verified"));
        assertTrue(f.service.describe(ALICE).key().isEmpty());
        assertEquals(0, f.backend.writes.get());

        f.bedrockPlayers.add(FLOOD);
        f.join(FLOOD);
        f.settle();
        assertTrue(f.service.describe(FLOOD).key().isEmpty());
        f.xuids.put(FLOOD, "not-an-xuid");
        f.join(FLOOD);
        assertTrue(f.service.describe(FLOOD).key().isEmpty());
        assertFalse(f.choose(FLOOD, Quality.DETAILED).saved());
        assertTrue(f.backend.rows.size() == 1);
    }

    @Test
    void distinctAccountsKeepTheirOwnChoiceEvenWithTheSameName() {
        TextureFixture f = fixture(true);
        f.join(ALICE);
        f.join(BOB);
        f.loadBase(ALICE);
        f.loadBase(BOB);
        f.settle();
        f.choose(ALICE, Quality.DETAILED);
        assertEquals(Quality.DETAILED, f.service.describe(ALICE).saved());
        assertEquals(Quality.STANDARD, f.service.describe(BOB).saved());
        assertTrue(f.service.describe(BOB).explicit().isEmpty());
        assertEquals(1, f.wire.overlayOffers());
        assertEquals(ALICE, f.wire.sent.getLast().player());
    }

    // Bedrock.

    @Test
    void bedrockSavesByXuidExplainsReconnectAndNeverTouchesJavaPacks() {
        TextureFixture f = fixture(true);
        f.bedrockPlayers.add(FLOOD);
        f.xuids.put(FLOOD, "2535400000001");
        f.bedrockActive.put(FLOOD, Quality.STANDARD);
        f.join(FLOOD);
        f.settle();
        var result = f.choose(FLOOD, Quality.DETAILED);
        assertTrue(result.saved());
        assertEquals("Saved: Detailed. Applies on your next connection. Pack loading cannot be observed.",
                result.message());
        assertFalse(result.message().toLowerCase().contains("loaded"));
        assertTrue(f.wire.sent.isEmpty());
        assertTrue(f.wire.removed.isEmpty());
        assertEquals(Quality.DETAILED, f.backend.rows.get(PlayerKey.bedrock("2535400000001")).quality());
        assertFalse(f.backend.rows.containsKey(PlayerKey.java(FLOOD)));
        var view = f.service.describe(FLOOD);
        assertEquals(Quality.DETAILED, view.saved());
        assertTrue(view.delivery().contains("this connection was given Standard"), view.delivery());
        assertTrue(view.delivery().contains("Detailed applies next time"));
        assertTrue(view.delivery().contains("cannot be observed"));
        assertTrue(f.wire.kicks.isEmpty());
    }

    @Test
    void bedrockDetailedThatIsNotConfiguredIsRefused() {
        TextureFixture f = fixture(true);
        f.bedrockDetailed = false;
        f.bedrockPlayers.add(FLOOD);
        f.xuids.put(FLOOD, "77");
        f.join(FLOOD);
        f.settle();
        assertFalse(f.choose(FLOOD, Quality.DETAILED).saved());
        var standard = f.choose(FLOOD, Quality.STANDARD);
        assertTrue(standard.saved());
        assertEquals("Saved: Standard. Applies on your next connection. Pack loading cannot be observed.",
                standard.message());
    }

    @Test
    void aBedrockAndAJavaPlayerWithTheSameNumberAreDifferentIdentities() {
        TextureFixture f = fixture(true);
        f.bedrockPlayers.add(FLOOD);
        f.xuids.put(FLOOD, "1");
        f.join(FLOOD);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        f.choose(FLOOD, Quality.DETAILED);
        assertEquals(Quality.STANDARD, f.service.describe(ALICE).saved());
        assertTrue(f.service.describe(ALICE).explicit().isEmpty());
        assertEquals(Quality.DETAILED, f.service.describe(FLOOD).saved());
    }

    // Admin reset.

    @Test
    void anAdminResetDurablySavesStandardForOfflineJavaAndBedrockIdentities() {
        TextureFixture f = fixture(true);
        f.saved(BOB, Quality.DETAILED);
        f.backend.rows.put(PlayerKey.bedrock("55"), new Stored(Quality.DETAILED, 4, 1));
        TextureService.Result[] results = new TextureService.Result[2];
        f.service.reset(PlayerKey.java(BOB), r -> results[0] = r);
        f.service.reset(PlayerKey.bedrock("55"), r -> results[1] = r);
        f.settle();
        assertTrue(results[0].saved() && results[1].saved());
        assertEquals(Quality.STANDARD, f.backend.rows.get(PlayerKey.java(BOB)).quality());
        assertEquals(Quality.STANDARD, f.backend.rows.get(PlayerKey.bedrock("55")).quality());
        assertEquals(5, f.backend.rows.get(PlayerKey.bedrock("55")).revision());
        assertEquals(Quality.STANDARD, f.store.snapshot(PlayerKey.bedrock("55")));
        assertEquals("Reset bedrock:55 to Standard.", results[1].message());
    }

    @Test
    void anAdminResetOfAnOnlineJavaPlayerRemovesTheirOverlayAndTellsThem() {
        TextureFixture f = fixture(true);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        f.choose(ALICE, Quality.DETAILED);
        f.overlayStatus(ALICE, Status.SUCCESSFULLY_LOADED);
        TextureService.Result[] result = new TextureService.Result[1];
        f.service.reset(PlayerKey.java(ALICE), r -> result[0] = r);
        f.settle();
        assertTrue(result[0].message().contains("overlay was removed"));
        assertEquals(List.of(new Removed(ALICE, DETAIL.id())), f.wire.removed);
        assertTrue(f.told.contains("An administrator reset your texture quality to Standard."));
        assertEquals(Quality.STANDARD, f.service.describe(ALICE).saved());
    }

    @Test
    void anAdminResetThatCannotWriteSaysSoAndLeavesTheRecord() {
        TextureFixture f = fixture(true);
        f.saved(BOB, Quality.DETAILED);
        f.backend.failWrites = true;
        TextureService.Result[] result = new TextureService.Result[1];
        f.service.reset(PlayerKey.java(BOB), r -> result[0] = r);
        f.settle();
        assertFalse(result[0].saved());
        assertEquals(Quality.DETAILED, f.backend.rows.get(PlayerKey.java(BOB)).quality());
    }

    // Both qualities keep ordinary behaviour: nothing here ever kicks.

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void overlayOutcomesNeverKickRegardlessOfTheRequiredSetting(boolean required) {
        TextureFixture f = fixture(required);
        f.join(ALICE);
        f.loadBase(ALICE);
        f.settle();
        f.choose(ALICE, Quality.DETAILED);
        f.overlayStatus(ALICE, Status.DECLINED);
        f.ticks.set(100_000);
        f.packs.tick();
        assertTrue(f.wire.kicks.isEmpty());
        assertTrue(f.packs.loaded(ALICE));
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
        assertEquals(OverlayState.NONE, f.packs.overlay(other));
    }
}
