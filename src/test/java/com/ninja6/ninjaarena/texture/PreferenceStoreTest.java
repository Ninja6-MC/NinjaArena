package com.ninja6.ninjaarena.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import com.ninja6.ninjaarena.texture.PreferenceStore.Health;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PreferenceStoreTest {

    private static final PlayerKey ALICE = PlayerKey.java(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final PlayerKey XBOX = PlayerKey.bedrock("2535400000001");

    private final MemoryBackend backend = new MemoryBackend();
    private final List<PreferenceStore> stores = new ArrayList<>();

    @AfterEach
    void closeAll() {
        stores.forEach(PreferenceStore::close);
    }

    private PreferenceStore start() throws Exception {
        PreferenceStore store = new PreferenceStore(backend.opener(), Logger.getLogger("test"), () -> 1234L,
                Duration.ofMillis(40));
        stores.add(store);
        store.start();
        if (!backend.failOpen) {
            PreferenceStorageTest.awaitReady(store);
        }
        return store;
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached");
    }

    @Test
    void aWriteIsInTheSnapshotByTheTimeItsFutureCompletes() throws Exception {
        PreferenceStore store = start();
        AtomicReference<Quality> seenByHandler = new AtomicReference<>();
        CompletableFuture<Stored> saved = store.save(ALICE, Quality.DETAILED);
        saved.thenAccept(stored -> seenByHandler.set(store.snapshot(ALICE))).get();
        assertEquals(Quality.DETAILED, seenByHandler.get());
        assertEquals(Quality.DETAILED, store.snapshot(ALICE));
        assertEquals(1234L, saved.get().updatedAt());
    }

    @Test
    void aFailedWriteIsNotPublishedAndLeavesTheStoredRecordAlone() throws Exception {
        PreferenceStore store = start();
        store.save(ALICE, Quality.DETAILED).get();
        backend.failWrites = true;
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> store.save(ALICE, Quality.STANDARD).get());
        assertTrue(failure.getCause() instanceof PreferenceStore.Unavailable);
        assertEquals(Quality.DETAILED, store.explicit(ALICE).orElseThrow());
        assertEquals(Quality.DETAILED, backend.rows.get(ALICE).quality());
        assertEquals(1, backend.rows.get(ALICE).revision());
    }

    @Test
    void writesAreSerializedInSubmissionOrder() throws Exception {
        PreferenceStore store = start();
        List<CompletableFuture<Stored>> all = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            all.add(store.save(ALICE, i % 2 == 0 ? Quality.STANDARD : Quality.DETAILED));
        }
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1, all.get(i).get().revision());
        }
        assertEquals(Quality.STANDARD, store.snapshot(ALICE));
        assertEquals(60, backend.rows.get(ALICE).revision());
    }

    @Test
    void whileTheDatabaseIsDownEveryoneGetsStandardAndNothingIsOverwritten() throws Exception {
        backend.rows.put(ALICE, new Stored(Quality.DETAILED, 3, 1));
        backend.failOpen = true;
        PreferenceStore store = start();
        await(() -> store.health() == Health.DEGRADED);
        assertEquals(Quality.STANDARD, store.snapshot(ALICE));
        assertFalse(store.isReady());
        assertTrue(store.problem().contains("cannot be reached"));
        assertThrows(ExecutionException.class, () -> store.save(ALICE, Quality.STANDARD).get());
        assertEquals(0, backend.writes.get());
        assertEquals(Quality.DETAILED, backend.rows.get(ALICE).quality());

        backend.failOpen = false;
        await(store::isReady);
        assertEquals(Quality.DETAILED, store.snapshot(ALICE));
        assertEquals("", store.problem());
        assertEquals(3, backend.rows.get(ALICE).revision());
    }

    @Test
    void aConnectionLostMidLifeDegradesThenRecoversWithoutLosingTheSnapshot() throws Exception {
        PreferenceStore store = start();
        store.save(ALICE, Quality.DETAILED).get();
        backend.failWrites = true;
        assertThrows(ExecutionException.class, () -> store.save(XBOX, Quality.DETAILED).get());
        assertEquals(Health.DEGRADED, store.health());
        assertEquals(Quality.STANDARD, store.snapshot(ALICE));
        backend.failWrites = false;
        await(store::isReady);
        assertEquals(Quality.DETAILED, store.snapshot(ALICE));
        assertEquals(Quality.STANDARD, store.snapshot(XBOX));
        assertEquals(Quality.DETAILED, store.save(XBOX, Quality.DETAILED).get().quality());
    }

    @Test
    void theReadyActionRunsAfterTheSnapshotLoadedAndNotBefore() throws Exception {
        backend.rows.put(ALICE, new Stored(Quality.DETAILED, 1, 1));
        backend.failOpen = true;
        PreferenceStore store = start();
        AtomicReference<Quality> seen = new AtomicReference<>();
        store.whenReady(() -> seen.set(store.snapshot(ALICE)));
        assertEquals(null, seen.get());
        backend.failOpen = false;
        await(() -> seen.get() != null);
        assertEquals(Quality.DETAILED, seen.get());
        AtomicReference<Quality> late = new AtomicReference<>();
        store.whenReady(() -> late.set(store.snapshot(ALICE)));
        assertEquals(Quality.DETAILED, late.get());
    }

    @Test
    void aStaleReadCannotReplaceANewerCommittedWrite() throws Exception {
        PreferenceStore store = start();
        store.save(ALICE, Quality.DETAILED).get();
        store.save(ALICE, Quality.STANDARD).get();
        backend.rows.put(ALICE, new Stored(Quality.DETAILED, 1, 0));
        assertEquals(Quality.DETAILED, store.load(ALICE).get().orElseThrow().quality());
        assertEquals(Quality.STANDARD, store.explicit(ALICE).orElseThrow());
    }
}
