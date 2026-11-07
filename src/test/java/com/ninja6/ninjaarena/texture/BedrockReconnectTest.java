package com.ninja6.ninjaarena.texture;

import static com.ninja6.ninjaarena.texture.TextureFixture.FLOOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.resourcepack.BedrockPackSettings;
import com.ninja6.ninjaarena.resourcepack.BedrockSelections;
import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Geyser negotiation with the real selection service, reading the committed in-memory preference. */
class BedrockReconnectTest {

    private static final UUID STANDARD = BedrockPackSettings.STANDARD_ID;
    private static final UUID DETAILED = BedrockPackSettings.DETAILED_ID;
    private static final UUID UNRELATED = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final String XUID = "2535400000001";

    private final TextureFixture fixture = new TextureFixture(true, true);
    private final BedrockSelections selections = new BedrockSelections(STANDARD, DETAILED, true);

    private final class Offer implements BedrockSelections.Negotiator {
        final Set<UUID> offered = new LinkedHashSet<>(List.of(STANDARD, DETAILED, UNRELATED));
        final AtomicInteger choices = new AtomicInteger();

        @Override public void remove(UUID pack) { offered.remove(pack); }
        @Override public void offer(Quality quality) {
            choices.incrementAndGet();
            offered.add(quality == Quality.STANDARD ? STANDARD : DETAILED);
        }
    }

    private void wire(BedrockSelections target) {
        fixture.store.whenReady(() -> target.selector(xuid -> fixture.store.snapshot(PlayerKey.bedrock(xuid))));
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void aNewPlayerNegotiatesExactlyOneStandardPackAndOthersSurvive() {
        wire(selections);
        Offer offer = new Offer();
        assertEquals(Quality.STANDARD, selections.negotiate(new Object(), XUID, offer));
        assertEquals(Set.of(UNRELATED, STANDARD), offer.offered);
        assertEquals(1, offer.choices.get());
    }

    @Test
    void aCommittedDetailedChoiceAppliesOnlyToTheNextConnectionAndTheCurrentOneStaysFrozen() {
        wire(selections);
        fixture.bedrockPlayers.add(FLOOD);
        fixture.xuids.put(FLOOD, XUID);
        fixture.join(FLOOD);
        fixture.settle();

        Object current = new Object();
        Offer first = new Offer();
        assertEquals(Quality.STANDARD, selections.negotiate(current, XUID, first));
        fixture.bedrockActive.put(FLOOD, Quality.STANDARD);

        var result = fixture.choose(FLOOD, Quality.DETAILED);
        assertTrue(result.saved());
        assertEquals(Quality.STANDARD, selections.negotiate(current, XUID, first));
        assertEquals(Quality.STANDARD, selections.active(current).orElseThrow());
        assertEquals(1, first.choices.get());
        assertEquals(Set.of(UNRELATED, STANDARD), first.offered);

        Offer next = new Offer();
        assertEquals(Quality.DETAILED, selections.negotiate(new Object(), XUID, next));
        assertEquals(Set.of(UNRELATED, DETAILED), next.offered);
        assertEquals(1, next.choices.get());
    }

    @Test
    void negotiationNeverTouchesTheDatabaseOrWaitsForIt() {
        wire(selections);
        fixture.settle();
        fixture.store.save(PlayerKey.bedrock(XUID), Quality.DETAILED).join();
        int loads = fixture.backend.loads.get();
        int writes = fixture.backend.writes.get();
        fixture.backend.failReads = true;
        fixture.backend.failWrites = true;
        assertEquals(Quality.DETAILED, selections.negotiate(new Object(), XUID, new Offer()));
        assertEquals(loads, fixture.backend.loads.get());
        assertEquals(writes, fixture.backend.writes.get());
    }

    @Test
    void whileStorageIsDownEveryoneGetsStandardWithoutOverwritingAnything() throws Exception {
        wire(selections);
        fixture.store.save(PlayerKey.bedrock(XUID), Quality.DETAILED).join();
        fixture.backend.failWrites = true;
        fixture.backend.failReads = true;
        try {
            fixture.store.save(PlayerKey.bedrock("9"), Quality.DETAILED).join();
        } catch (RuntimeException expected) {
            // The write fails and the store degrades.
        }
        assertEquals(PreferenceStore.Health.DEGRADED, fixture.store.health());
        assertEquals(Quality.STANDARD, selections.negotiate(new Object(), XUID, new Offer()));
        assertEquals(Quality.DETAILED, fixture.backend.rows.get(PlayerKey.bedrock(XUID)).quality());
    }

    @Test
    void theSelectorIsNotInstalledUntilTheSnapshotHasLoaded() {
        BedrockSelections cold = new BedrockSelections(STANDARD, DETAILED, true);
        MemoryBackend down = new MemoryBackend();
        down.failOpen = true;
        down.rows.put(PlayerKey.bedrock(XUID), new Stored(Quality.DETAILED, 1, 1));
        PreferenceStore store = new PreferenceStore(down.opener(), java.util.logging.Logger.getLogger("test"),
                () -> 1L, java.time.Duration.ofSeconds(60));
        try {
            store.whenReady(() -> cold.selector(xuid -> store.snapshot(PlayerKey.bedrock(xuid))));
            store.start();
            assertEquals(Quality.STANDARD, cold.negotiate(new Object(), XUID, new Offer()));
        } finally {
            store.close();
        }
    }

    @Test
    void identitiesAreIsolatedAndMissingOrOddIdentitiesGetStandard() {
        wire(selections);
        fixture.store.save(PlayerKey.bedrock(XUID), Quality.DETAILED).join();
        assertEquals(Quality.DETAILED, selections.negotiate(new Object(), XUID, new Offer()));
        assertEquals(Quality.STANDARD, selections.negotiate(new Object(), "2535400000002", new Offer()));
        assertEquals(Quality.STANDARD, selections.negotiate(new Object(), null, new Offer()));
        assertEquals(Quality.STANDARD, selections.negotiate(new Object(), "", new Offer()));
        assertEquals(Quality.STANDARD, selections.negotiate(new Object(), "Steve", new Offer()));
    }

    @Test
    void aDetailedPackThatIsNotAvailableFallsBackToStandardEvenWhenSaved() {
        BedrockSelections noDetailed = new BedrockSelections(STANDARD, DETAILED, false);
        wire(noDetailed);
        fixture.store.save(PlayerKey.bedrock(XUID), Quality.DETAILED).join();
        Offer offer = new Offer();
        assertEquals(Quality.STANDARD, noDetailed.negotiate(new Object(), XUID, offer));
        assertEquals(Set.of(UNRELATED, STANDARD), offer.offered);
        assertEquals(Quality.DETAILED, fixture.store.explicit(PlayerKey.bedrock(XUID)).orElseThrow());
    }
}
