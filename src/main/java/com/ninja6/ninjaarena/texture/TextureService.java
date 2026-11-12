package com.ninja6.ninjaarena.texture;

import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import com.ninja6.ninjaarena.resourcepack.ResourcePacks;
import com.ninja6.ninjaarena.resourcepack.ResourcePacks.OverlayState;
import com.ninja6.ninjaarena.texture.PreferenceStore.Health;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Connects the saved choice to delivery. {@link ResourcePacks} stays the authority for every
 * Java pack request; this class only decides when to ask it for the Detailed overlay, and
 * never records anything it cannot observe. Call it on the server thread only. Storage
 * completions hop back through the supplied executor and are checked against the connection
 * that asked.
 */
public final class TextureService implements ResourcePacks.Listener {

    /** Identity and Bedrock facts, kept behind an interface so no platform class is needed here. */
    public interface Identities {
        boolean bedrock(UUID player);

        /**
         * Whether Java account UUIDs are authenticated: online mode, a trusted online proxy or an
         * operator-declared login gate. When false, a Java player's choice lasts one connection.
         */
        boolean javaAuthenticated();

        /** The authenticated Xbox user id of a connected Bedrock player, if Geyser reports one. */
        Optional<String> xuid(UUID player);

        /** The quality frozen for this Bedrock connection at negotiation. */
        Optional<Quality> bedrockActive(UUID player);

        boolean bedrockDetailedAvailable();

        String bedrockStatus();
    }

    /** Sends a chat line to the player if they are still online. */
    @FunctionalInterface
    public interface Notifier {
        void tell(UUID player, String message);
    }

    /** What a choice came to. {@code saved} is true only after the write committed. */
    public record Result(boolean saved, String message) {
    }

    /** Everything the menu and status show for one player. */
    public record View(Optional<PlayerKey> key, boolean bedrock, Optional<Quality> explicit, Quality saved,
            boolean detailedAvailable, String delivery, String storage, boolean sessionOnly, String playerStatus,
            boolean storageUnsupported) {
    }

    private static final class Session {
        private final long id;
        private final Optional<PlayerKey> key;
        private Quality preference;
        private boolean loadFailed;
        /** A Java connection with no trusted identity: the choice lives here and nowhere else. */
        private final boolean sessionOnly;
        private long choice;

        private Session(long id, Optional<PlayerKey> key, boolean sessionOnly) {
            this.id = id;
            this.key = key;
            this.sessionOnly = sessionOnly;
        }
    }


    private final PreferenceStore store;
    private final ResourcePacks packs;
    private final Identities identities;
    private final Executor mainThread;
    private final Notifier notifier;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private long sequence;

    public TextureService(PreferenceStore store, ResourcePacks packs, Identities identities, Executor mainThread,
            Notifier notifier) {
        this.store = Objects.requireNonNull(store, "store");
        this.packs = Objects.requireNonNull(packs, "packs");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        packs.setListener(this);
    }

    /** The trusted identity, or empty: never guessed from a name. */
    Optional<PlayerKey> identity(UUID player) {
        try {
            if (identities.bedrock(player)) {
                return identities.xuid(player).map(PlayerKey::bedrock);
            }
            return identities.javaAuthenticated() ? Optional.of(PlayerKey.java(player)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Starts a connection's session and reads its saved choice off the server thread. */
    public void join(UUID player) {
        Optional<PlayerKey> identity = identity(player);
        Session session = new Session(++sequence, identity, identity.isEmpty() && !identities.bedrock(player));
        session.preference = Quality.STANDARD;
        sessions.put(player, session);
        if (session.key.isEmpty() || identities.bedrock(player)) {
            return;
        }
        PlayerKey key = session.key.get();
        store.load(key).whenComplete((stored, failure) -> mainThread.execute(() -> {
            Session current = sessions.get(player);
            if (current == null || current.id != session.id) {
                return;
            }
            if (failure != null) {
                current.loadFailed = true;
                Optional<Quality> remembered = store.explicit(key);
                current.preference = remembered.orElse(Quality.STANDARD);
                notifier.tell(player, text(remembered.isPresent() ? "texture.load-failed-remembered"
                        : "texture.load-failed"));
            } else {
                current.preference = stored.map(Stored::quality).orElse(Quality.STANDARD);
            }
            reconcile(player, current);
        }));
    }

    public void quit(UUID player) {
        sessions.remove(player);
    }

    public void closeAll() {
        sessions.clear();
    }

    private Session session(UUID player) {
        Session session = sessions.get(player);
        if (session == null) {
            join(player);
            session = sessions.get(player);
        }
        return session;
    }

    /**
     * Requests the overlay only when the saved choice is Detailed, Standard has loaded on this
     * connection and this connection has not used its one request. Safe to call repeatedly.
     */
    private void reconcile(UUID player, Session session) {
        if (session.preference != Quality.DETAILED || identities.bedrock(player)) {
            return;
        }
        if (packs.loaded(player) && packs.overlay(player) == OverlayState.NONE) {
            packs.addDetailed(player);
        }
    }

    @Override
    public void baseLoaded(UUID player) {
        Session session = sessions.get(player);
        if (session != null) {
            reconcile(player, session);
        }
    }

    @Override
    public void overlay(UUID player, OverlayState state, String reason) {
        switch (state) {
            case DECLINED -> notifier.tell(player, text("texture.overlay-declined", "kept", keptNote(player)));
            case FAILED -> notifier.tell(player, text("texture.overlay-failed",
                    "reason", ResourcePacks.describe(reason), "kept", keptNote(player)));
            case LOADED -> notifier.tell(player, text("texture.overlay-loaded"));
            default -> { }
        }
    }

    private String keptNote(UUID player) {
        Session session = sessions.get(player);
        return text(session != null && session.sessionOnly ? "texture.kept-session" : "texture.kept");
    }

    private boolean detailedAvailable(UUID player) {
        return identities.bedrock(player) ? identities.bedrockDetailedAvailable() : packs.detailedConfigured();
    }

    /** The saved choice and the delivery state, for the menu and status. */
    public View describe(UUID player) {
        Session session = session(player);
        boolean bedrock = identities.bedrock(player);
        Optional<Quality> explicit = session.sessionOnly ? Optional.of(session.preference)
                : session.key.flatMap(store::explicit);
        Quality saved = explicit.orElse(Quality.STANDARD);
        String storage = session.sessionOnly ? "" : switch (store.health()) {
            case READY -> "";
            case STARTING -> text("texture.storage-starting");
            case DEGRADED -> text("texture.storage-unavailable");
            case UNSUPPORTED -> text("texture.storage-unsupported", "problem", store.problem());
        };
        String delivery;
        if (session.sessionOnly) {
            delivery = text("texture.delivery-session", "delivery", javaDelivery(player, saved),
                    "note", text("texture.session-note"));
        } else if (session.key.isEmpty()) {
            delivery = text("texture.delivery-unverified");
        } else if (store.health() != Health.READY && explicit.isEmpty()) {
            delivery = text("texture.delivery-unavailable");
        } else if (bedrock) {
            delivery = bedrockDelivery(player, saved);
        } else {
            delivery = javaDelivery(player, saved);
        }
        return new View(session.key, bedrock, explicit, saved, detailedAvailable(player), delivery, storage,
                session.sessionOnly, playerStatus(player, bedrock),
                !session.sessionOnly && store.health() == Health.UNSUPPORTED);
    }

    /** Short player wording; the full delivery diagnostics remain in {@link View#delivery()}. */
    private String playerStatus(UUID player, boolean bedrock) {
        if (bedrock) {
            return identities.bedrockActive(player).map(quality -> text("texture.status.bedrock-sent",
                    "quality", display(quality))).orElseGet(() -> text("texture.status.bedrock-unknown"));
        }
        String base = text(packs.loaded(player) ? "texture.status.standard-active"
                : "texture.status.standard-missing");
        return switch (packs.overlay(player)) {
            case LOADED -> text("texture.status.detailed-active");
            case PENDING -> text("texture.status.detailed-loading", "base", base);
            case DECLINED -> text("texture.status.detailed-declined", "base", base);
            case FAILED -> text("texture.status.detailed-failed", "base", base);
            case CANCELLED, NONE -> text("texture.status.standard", "base", base);
        };
    }

    private String bedrockDelivery(UUID player, Quality saved) {
        String note = identities.bedrockStatus();
        Optional<Quality> active = identities.bedrockActive(player);
        String now = active.map(quality -> text("texture.bedrock.given", "quality", display(quality)))
                .orElseGet(() -> text("texture.bedrock.unknown"));
        if (active.isPresent() && active.get() != saved) {
            now = text("texture.bedrock.changed", "now", now, "quality", display(saved));
        }
        return text("texture.bedrock.delivery", "now", now, "tools", note);
    }

    private String javaDelivery(UUID player, Quality saved) {
        OverlayState overlay = packs.overlay(player);
        boolean baseLoaded = packs.loaded(player);
        String base = text(baseLoaded ? "texture.java.standard-active" : "texture.java.standard-missing");
        if (saved == Quality.DETAILED && !packs.detailedConfigured()) {
            return text("texture.java.detailed-not-set-up", "base", base);
        }
        return switch (overlay) {
            case LOADED -> text("texture.java.detailed-active");
            case PENDING -> text("texture.java.detailed-pending", "base", base);
            case DECLINED -> text("texture.java.detailed-declined", "base", base);
            case FAILED -> text("texture.java.detailed-failed", "base", base,
                    "reason", ResourcePacks.describe(packs.overlayReason(player)));
            case CANCELLED -> text("texture.java.detailed-removed", "base", base);
            case NONE -> saved == Quality.DETAILED
                    ? text(baseLoaded ? "texture.java.detailed-not-requested" : "texture.java.detailed-waiting",
                            "base", base)
                    : base;
        };
    }

    static String display(Quality quality) {
        return text(quality == Quality.DETAILED ? "texture.detailed" : "texture.standard");
    }

    /**
     * Saves an explicit choice. Choosing Standard on Java cancels any pending Detailed request and
     * removes the overlay first. {@code done} runs on the server thread once the write has
     * committed or failed; nothing is reported as saved before then.
     */
    public void choose(UUID player, Quality quality, Consumer<Result> done) {
        Session session = session(player);
        boolean bedrock = identities.bedrock(player);
        if (session.key.isEmpty() && !session.sessionOnly) {
            done.accept(new Result(false, text("texture.bedrock-unverified")));
            return;
        }
        if (quality == Quality.DETAILED && !detailedAvailable(player)) {
            done.accept(new Result(false, text("texture.detailed-unavailable")));
            return;
        }
        if (session.sessionOnly) {
            session.preference = quality;
            if (quality == Quality.STANDARD) {
                packs.removeDetailed(player);
            } else {
                reconcile(player, session);
            }
            done.accept(new Result(false, savedMessage(player, quality, true, true)));
            return;
        }
        if (!bedrock && quality == Quality.STANDARD) {
            session.preference = Quality.STANDARD;
            packs.removeDetailed(player);
        }
        long choice = ++session.choice;
        PlayerKey key = session.key.get();
        store.save(key, quality).whenComplete((stored, failure) -> mainThread.execute(() -> {
            Session current = sessions.get(player);
            boolean live = current != null && current.id == session.id;
            if (failure != null) {
                done.accept(new Result(false, text(!bedrock && quality == Quality.STANDARD
                        ? "texture.save-failed-removed" : "texture.save-failed")));
                return;
            }
            if (live && !bedrock && current.choice == choice) {
                current.preference = quality;
                if (quality == Quality.DETAILED) {
                    reconcile(player, current);
                }
            }
            done.accept(new Result(true, savedMessage(player, quality, live, false)));
        }));
    }

    private String savedMessage(UUID player, Quality quality, boolean live, boolean sessionOnly) {
        String saved = sessionOnly ? text("texture.chosen-session", "quality", display(quality),
                "note", text("texture.session-note")) : text("texture.saved", "quality", display(quality));
        String next;
        if (identities.bedrock(player)) {
            next = "texture.next.bedrock";
        } else if (!live) {
            next = "texture.next.next-connection";
        } else if (quality == Quality.STANDARD) {
            next = "texture.next.overlay-removed";
        } else {
            next = switch (packs.overlay(player)) {
                case PENDING -> "texture.next.requesting";
                case LOADED -> "texture.next.active";
                case NONE -> "texture.next.after-standard";
                default -> sessionOnly ? "texture.next.used-session" : "texture.next.used";
            };
        }
        return text(next, "saved", saved);
    }

    /**
     * Admin recovery: durably saves Standard for an identity, online or not, and removes the
     * overlay if that Java player is connected.
     */
    public void reset(PlayerKey key, Consumer<Result> done) {
        store.save(key, Quality.STANDARD).whenComplete((stored, failure) -> mainThread.execute(() -> {
            if (failure != null) {
                done.accept(new Result(false, text("texture.save-failed")));
                return;
            }
            boolean removed = false;
            Optional<UUID> account = key.account();
            if (account.isPresent() && sessions.containsKey(account.get())) {
                Session current = sessions.get(account.get());
                current.preference = Quality.STANDARD;
                current.choice++;
                removed = packs.overlay(account.get()) == OverlayState.PENDING
                        || packs.overlay(account.get()) == OverlayState.LOADED;
                packs.removeDetailed(account.get());
                notifier.tell(account.get(), text("texture.reset-notice"));
            }
            done.accept(new Result(true, text(removed ? "texture.reset-removed" : "texture.reset", "player", key)));
        }));
    }
}
