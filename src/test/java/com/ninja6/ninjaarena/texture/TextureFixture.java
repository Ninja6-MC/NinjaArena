package com.ninja6.ninjaarena.texture;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import com.ninja6.ninjaarena.resourcepack.ResourcePackSettings;
import com.ninja6.ninjaarena.resourcepack.ResourcePackSettings.Pack;
import com.ninja6.ninjaarena.resourcepack.ResourcePacks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** A service, store, pack authority and fake platform wired together without a server or client. */
final class TextureFixture implements TextureService.Identities, AutoCloseable {

    static final Pack BASE = new Pack(UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
            "https://example.org/standard-v1.zip", "a".repeat(40));
    static final Pack DETAIL = new Pack(UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
            "https://example.org/detailed-v1.zip", "b".repeat(40));
    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UUID FLOOD = UUID.fromString("00000000-0000-0000-0009-000000000001");

    record Sent(UUID player, Pack pack, boolean required) { }
    record Removed(UUID player, UUID pack) { }

    final class Wire implements ResourcePacks.Transport {
        final List<Sent> sent = new ArrayList<>();
        final List<Removed> removed = new ArrayList<>();
        final List<String> kicks = new ArrayList<>();
        boolean breakOverlaySend;

        @Override
        public void send(UUID player, Pack pack, boolean required) {
            if (breakOverlaySend && pack.id().equals(DETAIL.id())) {
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
        }

        long overlayOffers() {
            return sent.stream().filter(s -> s.pack().id().equals(DETAIL.id())).count();
        }
    }

    final MemoryBackend backend = new MemoryBackend();
    final Wire wire = new Wire();
    final AtomicLong ticks = new AtomicLong();
    final ConcurrentLinkedQueue<Runnable> main = new ConcurrentLinkedQueue<>();
    final List<String> told = new ArrayList<>();
    final Set<UUID> bedrockPlayers = new HashSet<>();
    final Map<UUID, String> xuids = new HashMap<>();
    final Map<UUID, Quality> bedrockActive = new HashMap<>();
    boolean authenticated = true;
    boolean bedrockDetailed = true;
    final PreferenceStore store;
    final ResourcePacks packs;
    final TextureService service;

    TextureFixture(boolean required, boolean detailedConfigured) {
        this(required, detailedConfigured, DETAIL);
    }

    TextureFixture(boolean required, boolean detailedConfigured, Pack detail) {
        store = new PreferenceStore(backend.opener(), Logger.getLogger("test"), () -> 99L, Duration.ofMillis(30));
        packs = new ResourcePacks(new ResourcePackSettings(true, required, true, 120, BASE,
                detailedConfigured ? Optional.of(detail) : Optional.empty()), wire, ticks::get, bedrockPlayers::contains);
        service = new TextureService(store, packs, this, main::add, (id, message) -> told.add(message));
        store.start();
        try {
            PreferenceStorageTest.awaitReady(store);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs every queued store completion and server-thread hop until nothing is left. */
    void settle() {
        for (int round = 0; round < 6; round++) {
            try {
                store.load(PlayerKey.java(UUID.fromString("00000000-0000-0000-ffff-ffffffffffff"))).get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // A down store still lets queued hops run.
            }
            Runnable task;
            while ((task = main.poll()) != null) {
                task.run();
            }
        }
    }

    void join(UUID player) {
        packs.join(player);
        service.join(player);
    }

    void loadBase(UUID player) {
        packs.status(player, BASE.id(), org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
    }

    void overlayStatus(UUID player, org.bukkit.event.player.PlayerResourcePackStatusEvent.Status status) {
        packs.status(player, DETAIL.id(), status);
    }

    /** Chooses and settles; returns the result. */
    TextureService.Result choose(UUID player, Quality quality) {
        TextureService.Result[] result = new TextureService.Result[1];
        service.choose(player, quality, value -> result[0] = value);
        settle();
        return result[0];
    }

    /** The view and stored row for a player, for comparing two runs. */
    String describeAll(UUID player) {
        return service.describe(player) + "|" + backend.rows.get(PlayerKey.java(player));
    }

    void saved(UUID player, Quality quality) {
        backend.rows.put(PlayerKey.java(player), new Stored(quality, 1, 1));
    }

    @Override public boolean bedrock(UUID player) { return bedrockPlayers.contains(player); }
    @Override public boolean javaAuthenticated() { return authenticated; }
    @Override public Optional<String> xuid(UUID player) { return Optional.ofNullable(xuids.get(player)); }
    @Override public Optional<Quality> bedrockActive(UUID player) { return Optional.ofNullable(bedrockActive.get(player)); }
    @Override public boolean bedrockDetailedAvailable() { return bedrockDetailed; }
    @Override public String bedrockStatus() { return "custom held models eligible (client load unobserved)"; }

    @Override
    public void close() {
        store.close();
    }
}
