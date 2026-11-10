package com.ninja6.ninjaarena.resourcepack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.resourcepack.ResourcePackSettings.Pack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ResourcePacksTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Pack BASE = new Pack(UUID.randomUUID(), "https://example.org/standard-v1.zip", "a".repeat(40));
    private static final Pack DETAIL = new Pack(UUID.randomUUID(), "https://example.org/detailed-v1.zip", "b".repeat(40));

    private record Sent(UUID player, Pack pack, boolean required) { }
    private record Removed(UUID player, UUID pack) { }

    private static final class Wire implements ResourcePacks.Transport {
        private final List<Sent> sent = new ArrayList<>();
        private final List<Removed> removed = new ArrayList<>();
        private final List<String> kicks = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private boolean breakSend;

        @Override
        public void send(UUID player, Pack pack, boolean required) {
            if (breakSend) {
                throw new IllegalArgumentException("send failed");
            }
            sent.add(new Sent(player, pack, required));
        }

        @Override
        public void remove(UUID player, UUID pack) {
            removed.add(new Removed(player, pack));
        }

        @Override
        public void kick(UUID player, String message) {
            kicks.add(message);
        }

        @Override
        public void error(String message, RuntimeException failure) {
            errors.add(message);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    }

    private final Wire wire = new Wire();
    private final AtomicLong ticks = new AtomicLong();

    private ResourcePacks packs(boolean required) {
        return new ResourcePacks(new ResourcePackSettings(true, required, true, 120, BASE, Optional.of(DETAIL)),
                wire, ticks::get);
    }

    private void load(ResourcePacks packs) {
        packs.join(PLAYER);
        packs.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
    }

    @Test
    void bedrockNeverReceivesJavaOffersOrAcquiresLoadedStateFromSyntheticStatuses() {
        var packs = new ResourcePacks(new ResourcePackSettings(true, true, true, 120, BASE, Optional.of(DETAIL)),
                wire, ticks::get, id -> id.equals(PLAYER));
        packs.join(PLAYER);
        for (Status status : Status.values()) packs.status(PLAYER, BASE.id(), status);
        ticks.set(100_000);
        packs.tick();
        assertFalse(packs.loaded(PLAYER));
        assertFalse(packs.addDetailed(PLAYER));
        assertTrue(wire.sent.isEmpty());
        assertTrue(wire.kicks.isEmpty());
        assertTrue(wire.removed.isEmpty());
        UUID java = UUID.randomUUID();
        packs.join(java);
        packs.status(java, BASE.id(), Status.SUCCESSFULLY_LOADED);
        assertTrue(packs.loaded(java));
        assertEquals(1, wire.sent.size());
    }

    @Test
    void disabledDeliveryNeitherPromptsNorEnforces() {
        ResourcePacks packs = new ResourcePacks(ResourcePackSettings.OFF, wire, ticks::get);
        packs.join(PLAYER);
        ticks.set(10_000);
        packs.tick();
        packs.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
        assertFalse(packs.loaded(PLAYER));
        assertFalse(packs.addDetailed(PLAYER));
        packs.close();
        assertTrue(wire.sent.isEmpty());
        assertTrue(wire.kicks.isEmpty());
        assertTrue(wire.removed.isEmpty());
    }

    @Test
    void requiredJoinSendsStandardOnceWithIdentityAndIntegrity() {
        ResourcePacks packs = packs(true);
        packs.join(PLAYER);
        packs.join(PLAYER);
        assertEquals(List.of(new Sent(PLAYER, BASE, true)), wire.sent);
        assertFalse(packs.loaded(PLAYER));
        assertFalse(packs.addDetailed(PLAYER));
    }

    @Test
    void acceptanceAndDownloadAreNotProofOfLoading() {
        ResourcePacks packs = packs(false);
        packs.join(PLAYER);
        packs.status(PLAYER, BASE.id(), Status.ACCEPTED);
        assertFalse(packs.loaded(PLAYER));
        packs.status(PLAYER, BASE.id(), Status.DOWNLOADED);
        assertFalse(packs.loaded(PLAYER));
        packs.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
        assertTrue(packs.loaded(PLAYER));
        packs.status(PLAYER, BASE.id(), Status.ACCEPTED);
        packs.status(PLAYER, BASE.id(), Status.DOWNLOADED);
        assertTrue(packs.loaded(PLAYER));
        ticks.set(10_000);
        packs.tick();
        assertTrue(packs.loaded(PLAYER));
        assertTrue(wire.kicks.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"DECLINED", "FAILED_DOWNLOAD", "FAILED_RELOAD", "INVALID_URL", "DISCARDED"})
    void requiredFailureKicksOnceAndNeverReprompts(Status failure) {
        ResourcePacks packs = packs(true);
        packs.join(PLAYER);
        packs.status(PLAYER, BASE.id(), failure);
        packs.status(PLAYER, BASE.id(), failure);
        packs.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
        ticks.set(10_000);
        packs.tick();
        packs.join(PLAYER);
        assertFalse(packs.loaded(PLAYER));
        assertEquals(1, wire.kicks.size());
        assertTrue(wire.kicks.getFirst().contains("required resource pack"));
        assertTrue(wire.kicks.getFirst().contains("reconnect"));
        assertEquals(1, wire.sent.size());
        assertEquals(List.of(new Removed(PLAYER, BASE.id())), wire.removed);
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"DECLINED", "FAILED_DOWNLOAD", "FAILED_RELOAD", "INVALID_URL", "DISCARDED"})
    void optionalFailureContinuesWithoutCustomVisualsOrRetries(Status failure) {
        ResourcePacks packs = packs(false);
        packs.join(PLAYER);
        packs.status(PLAYER, BASE.id(), failure);
        packs.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
        packs.join(PLAYER);
        assertFalse(packs.loaded(PLAYER));
        assertTrue(wire.kicks.isEmpty());
        assertEquals(1, wire.sent.size());
        assertFalse(wire.sent.getFirst().required());
    }

    @Test
    void timeoutHasOneDeadlineEvenWithProgress() {
        ResourcePacks packs = packs(true);
        packs.join(PLAYER);
        ticks.set(2_399);
        packs.tick();
        packs.status(PLAYER, BASE.id(), Status.ACCEPTED);
        assertTrue(wire.kicks.isEmpty());
        ticks.incrementAndGet();
        packs.tick();
        packs.tick();
        assertFalse(packs.loaded(PLAYER));
        assertEquals(1, wire.kicks.size());
        assertTrue(wire.kicks.getFirst().contains("timed out"));
    }

    @Test
    void optionalTimeoutDoesNotKickAndCannotBecomeLoadedFromALateReply() {
        ResourcePacks packs = packs(false);
        packs.join(PLAYER);
        ticks.set(2_400);
        packs.tick();
        packs.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
        assertFalse(packs.loaded(PLAYER));
        assertTrue(wire.kicks.isEmpty());
        assertEquals(1, wire.sent.size());
    }

    @Test
    void statusesForOtherPluginsAndOtherPlayersCannotUnlockThePack() {
        ResourcePacks packs = packs(true);
        packs.join(PLAYER);
        packs.status(PLAYER, UUID.randomUUID(), Status.SUCCESSFULLY_LOADED);
        packs.status(UUID.randomUUID(), BASE.id(), Status.SUCCESSFULLY_LOADED);
        packs.status(PLAYER, DETAIL.id(), Status.SUCCESSFULLY_LOADED);
        assertFalse(packs.loaded(PLAYER));
        assertTrue(wire.kicks.isEmpty());
        assertTrue(wire.removed.isEmpty());
    }

    @Test
    void cacheReuseStillWaitsForLoadedAndReconnectSendsTheSameHash() {
        ResourcePacks packs = packs(true);
        load(packs);
        packs.quit(PLAYER);
        assertFalse(packs.loaded(PLAYER));
        packs.join(PLAYER);
        assertFalse(packs.loaded(PLAYER));
        packs.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
        assertTrue(packs.loaded(PLAYER));
        assertEquals(List.of(new Sent(PLAYER, BASE, true), new Sent(PLAYER, BASE, true)), wire.sent);
    }

    @Test
    void versionUpdatePreservesIdentityButSendsNewPinnedUrlAndHash() {
        ResourcePacks old = packs(true);
        load(old);
        old.close();
        Pack updated = new Pack(BASE.id(), "https://example.org/standard-v2.zip", "c".repeat(40));
        ResourcePacks next = new ResourcePacks(new ResourcePackSettings(true, true, true, 120, updated,
                Optional.empty()), wire, ticks::get);
        next.join(PLAYER);
        assertFalse(next.loaded(PLAYER));
        assertEquals(new Sent(PLAYER, updated, true), wire.sent.getLast());
        next.status(PLAYER, updated.id(), Status.SUCCESSFULLY_LOADED);
        assertTrue(next.loaded(PLAYER));
    }

    @Test
    void detailedRequiresExplicitRequestAndRemovesOnlyItsOwnIdentity() {
        ResourcePacks packs = packs(true);
        load(packs);
        assertEquals(1, wire.sent.size());
        assertTrue(packs.addDetailed(PLAYER));
        assertFalse(packs.addDetailed(PLAYER));
        assertEquals(new Sent(PLAYER, DETAIL, false), wire.sent.getLast());
        packs.status(PLAYER, DETAIL.id(), Status.SUCCESSFULLY_LOADED);
        packs.removeDetailed(PLAYER);
        packs.removeDetailed(PLAYER);
        assertEquals(List.of(new Removed(PLAYER, DETAIL.id())), wire.removed);
        assertTrue(packs.loaded(PLAYER));
        assertTrue(wire.kicks.isEmpty());
        packs.status(PLAYER, DETAIL.id(), Status.FAILED_RELOAD);
        assertTrue(packs.loaded(PLAYER));
    }

    @Test
    void detailedFailureAndTimeoutNeverEnforceTheOptionalOverlayAndEachConnectionGetsOneOffer() {
        ResourcePacks packs = packs(true);
        load(packs);
        assertTrue(packs.addDetailed(PLAYER));
        packs.status(PLAYER, DETAIL.id(), Status.FAILED_RELOAD);
        assertTrue(packs.loaded(PLAYER));
        assertEquals(ResourcePacks.OverlayState.FAILED, packs.overlay(PLAYER));
        assertEquals("could not be reloaded", packs.overlayReason(PLAYER));
        assertFalse(packs.addDetailed(PLAYER));
        packs.quit(PLAYER);
        load(packs);
        assertEquals(ResourcePacks.OverlayState.NONE, packs.overlay(PLAYER));
        assertTrue(packs.addDetailed(PLAYER));
        ticks.set(2_400);
        packs.tick();
        assertTrue(packs.loaded(PLAYER));
        assertEquals(ResourcePacks.OverlayState.FAILED, packs.overlay(PLAYER));
        assertEquals("timed out while loading", packs.overlayReason(PLAYER));
        assertFalse(packs.addDetailed(PLAYER));
        assertTrue(wire.kicks.isEmpty());
        assertEquals(List.of(new Removed(PLAYER, DETAIL.id()), new Removed(PLAYER, DETAIL.id())), wire.removed);
    }

    @Test
    void overlayStatesAndListenerFollowTheClientsReports() {
        ResourcePacks packs = packs(false);
        List<String> events = new ArrayList<>();
        packs.setListener(new ResourcePacks.Listener() {
            @Override public void baseLoaded(UUID player) { events.add("base"); }
            @Override public void overlay(UUID player, ResourcePacks.OverlayState state, String reason) {
                events.add(state + (reason.isEmpty() ? "" : ":" + reason));
            }
        });
        assertTrue(packs.detailedConfigured());
        packs.join(PLAYER);
        packs.status(PLAYER, BASE.id(), Status.ACCEPTED);
        packs.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
        packs.status(PLAYER, BASE.id(), Status.SUCCESSFULLY_LOADED);
        assertTrue(packs.addDetailed(PLAYER));
        packs.status(PLAYER, DETAIL.id(), Status.ACCEPTED);
        packs.status(PLAYER, DETAIL.id(), Status.DOWNLOADED);
        assertEquals(ResourcePacks.OverlayState.PENDING, packs.overlay(PLAYER));
        packs.status(PLAYER, DETAIL.id(), Status.SUCCESSFULLY_LOADED);
        assertEquals(ResourcePacks.OverlayState.LOADED, packs.overlay(PLAYER));
        packs.removeDetailed(PLAYER);
        assertEquals(List.of("base", "PENDING", "LOADED", "CANCELLED"), events);
        assertEquals(ResourcePacks.OverlayState.NONE, packs.overlay(UUID.randomUUID()));
    }

    @Test
    void declineIsADistinctOverlayOutcomeFromFailure() {
        ResourcePacks packs = packs(true);
        load(packs);
        packs.addDetailed(PLAYER);
        packs.status(PLAYER, DETAIL.id(), Status.DECLINED);
        assertEquals(ResourcePacks.OverlayState.DECLINED, packs.overlay(PLAYER));
        assertTrue(packs.loaded(PLAYER));
        assertTrue(wire.kicks.isEmpty());
    }

    @Test
    void anUnconfiguredOverlayIsNeverRequested() {
        ResourcePacks packs = new ResourcePacks(new ResourcePackSettings(true, true, true, 120, BASE, Optional.empty()),
                wire, ticks::get);
        assertFalse(packs.detailedConfigured());
        load(packs);
        assertFalse(packs.addDetailed(PLAYER));
        assertEquals(ResourcePacks.OverlayState.NONE, packs.overlay(PLAYER));
    }

    @Test
    void losingTheBaseRevokesDetailedAndEnforcesRequiredExactlyOnce() {
        ResourcePacks packs = packs(true);
        load(packs);
        packs.addDetailed(PLAYER);
        packs.status(PLAYER, BASE.id(), Status.DISCARDED);
        packs.status(PLAYER, BASE.id(), Status.DISCARDED);
        assertFalse(packs.loaded(PLAYER));
        assertFalse(packs.addDetailed(PLAYER));
        assertEquals(List.of(new Removed(PLAYER, DETAIL.id()), new Removed(PLAYER, BASE.id())), wire.removed);
        assertEquals(1, wire.kicks.size());
    }

    @Test
    void closeOnlyRemovesRequestedOwnPacksAndCannotRestart() {
        ResourcePacks packs = packs(true);
        load(packs);
        packs.addDetailed(PLAYER);
        packs.close();
        packs.close();
        packs.join(PLAYER);
        assertFalse(packs.loaded(PLAYER));
        assertEquals(2, wire.sent.size());
        assertEquals(List.of(new Removed(PLAYER, DETAIL.id()), new Removed(PLAYER, BASE.id())), wire.removed);
        assertTrue(wire.kicks.isEmpty());
    }

    @Test
    void synchronousSendFailureIsTerminalAndReportsOperatorError() {
        ResourcePacks packs = packs(true);
        wire.breakSend = true;
        packs.join(PLAYER);
        packs.join(PLAYER);
        assertFalse(packs.loaded(PLAYER));
        assertEquals(1, wire.errors.size());
        assertEquals(1, wire.kicks.size());
    }

    @Test
    void requiredDownloadFailureNamesOnlyTheConfiguredHostAndSuggestsReconnecting() {
        Pack base = new Pack(BASE.id(), "https://packs.example.net:8443/private/standard.zip?token=secret",
                BASE.sha1());
        ResourcePacks packs = new ResourcePacks(
                new ResourcePackSettings(true, true, true, 120, base, Optional.empty()), wire, ticks::get);
        packs.join(PLAYER);
        packs.status(PLAYER, base.id(), Status.FAILED_DOWNLOAD);
        assertEquals(List.of("Your client could not reach NinjaArena's required resource pack host "
                + "packs.example.net. Please reconnect; reconnecting usually works."
                + " If it still fails, contact the server operator."), wire.kicks);
        assertEquals(List.of("The client for " + PLAYER + " could not reach the required resource pack host "
                + "packs.example.net. Ask the player to reconnect; reconnecting usually works."
                + " If failures persist, check the pack host's availability and"
                + " resource-pack.url and resource-pack.sha1."), wire.warnings);
    }

    @Test
    void aRequiredPackFailureLeavesAnOperatorWarningButADeclineDoesNot() {
        ResourcePacks packs = packs(true);
        packs.join(PLAYER);
        packs.status(PLAYER, BASE.id(), Status.FAILED_DOWNLOAD);
        assertEquals(1, wire.warnings.size());
        assertTrue(wire.warnings.getFirst().contains("example.org"));
        packs.quit(PLAYER);
        packs.join(PLAYER);
        packs.status(PLAYER, BASE.id(), Status.DECLINED);
        assertEquals(1, wire.warnings.size());
    }
}
