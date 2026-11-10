package com.ninja6.ninjaarena.texture;

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
            boolean detailedAvailable, String delivery, String storage, boolean sessionOnly, String playerStatus) {
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

    /** Start of the storage line for a store that can never work; the dialog words it differently. */
    static final String STORAGE_UNSUPPORTED = "Storage is unsupported";

    public static final String SAVE_FAILED = "Could not save: texture preference storage is unavailable."
            + " Nothing was changed.";
    public static final String SESSION_ONLY_NOTE = "This is not saved and resets when you leave.";
    public static final String BEDROCK_SAVED_TAIL = " Applies on your next connection. Pack loading cannot be observed.";

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
                notifier.tell(player, "Your saved texture quality could not be read, so "
                        + (remembered.isPresent() ? "the last known choice" : "Standard") + " is used for now.");
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
            case DECLINED -> notifier.tell(player, "Detailed was declined. Standard stays active and "
                    + keptNote(player));
            case FAILED -> notifier.tell(player, "Detailed could not be loaded (" + reason + "). Standard stays"
                    + " active and " + keptNote(player));
            case LOADED -> notifier.tell(player, "Detailed is now active.");
            default -> { }
        }
    }

    private String keptNote(UUID player) {
        Session session = sessions.get(player);
        return session != null && session.sessionOnly
                ? "your Detailed choice was for this connection only and is not saved."
                : "your Detailed choice is kept for your next connection.";
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
            case STARTING -> "Storage is still starting; Standard is used until it is ready.";
            case DEGRADED -> "Storage is unavailable; Standard is used and choices cannot be saved.";
            case UNSUPPORTED -> STORAGE_UNSUPPORTED + " (" + store.problem() + "); Standard is used.";
        };
        String delivery;
        if (session.sessionOnly) {
            delivery = javaDelivery(player, saved) + ". Your choice is for this connection only: " + SESSION_ONLY_NOTE;
        } else if (session.key.isEmpty()) {
            delivery = "Standard (your account could not be verified, so nothing is saved)";
        } else if (store.health() != Health.READY && explicit.isEmpty()) {
            delivery = "Standard (saved choices are unavailable)";
        } else if (bedrock) {
            delivery = bedrockDelivery(player, saved);
        } else {
            delivery = javaDelivery(player, saved);
        }
        return new View(session.key, bedrock, explicit, saved, detailedAvailable(player), delivery, storage,
                session.sessionOnly, playerStatus(player, bedrock));
    }

    /** Short player wording; the full delivery diagnostics remain in {@link View#delivery()}. */
    private String playerStatus(UUID player, boolean bedrock) {
        if (bedrock) {
            return identities.bedrockActive(player).map(quality -> display(quality) + " sent on connect.")
                    .orElse("Current pack is unknown.");
        }
        String base = packs.loaded(player) ? "Standard active" : "Standard not loaded";
        return switch (packs.overlay(player)) {
            case LOADED -> "Detailed active.";
            case PENDING -> base + "; Detailed loading.";
            case DECLINED -> base + "; Detailed declined.";
            case FAILED -> base + "; Detailed failed.";
            case CANCELLED, NONE -> base + ".";
        };
    }

    private String bedrockDelivery(UUID player, Quality saved) {
        String note = identities.bedrockStatus();
        Optional<Quality> active = identities.bedrockActive(player);
        String now = active.map(quality -> "this connection was given " + display(quality)
                + " when you connected").orElse("this connection's selection is not known");
        String change = active.isPresent() && active.get() != saved ? "; " + display(saved) + " applies next time" : "";
        return "Bedrock: " + now + change + ". Pack loading cannot be observed. Custom tools: " + note;
    }

    private String javaDelivery(UUID player, Quality saved) {
        OverlayState overlay = packs.overlay(player);
        boolean baseLoaded = packs.loaded(player);
        String base = baseLoaded ? "Standard is active" : "Standard has not loaded on this connection";
        if (saved == Quality.DETAILED && !packs.detailedConfigured()) {
            return base + "; Detailed is saved but not set up on this server";
        }
        return switch (overlay) {
            case LOADED -> "Detailed is active";
            case PENDING -> base + "; Detailed was requested and is waiting for your client";
            case DECLINED -> base + "; Detailed was declined";
            case FAILED -> base + "; Detailed failed (" + packs.overlayReason(player) + ")";
            case CANCELLED -> base + "; Detailed was removed for this connection";
            case NONE -> saved == Quality.DETAILED
                    ? base + (baseLoaded ? "; Detailed has not been requested" : "; Detailed waits for Standard to load")
                    : base;
        };
    }

    private static String display(Quality quality) {
        return quality == Quality.DETAILED ? "Detailed" : "Standard";
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
            done.accept(new Result(false, "Your Bedrock account could not be verified, so Standard is used and"
                    + " nothing can be saved."));
            return;
        }
        if (quality == Quality.DETAILED && !detailedAvailable(player)) {
            done.accept(new Result(false, "Detailed is not set up on this server, so nothing was changed."));
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
                done.accept(new Result(false, !bedrock && quality == Quality.STANDARD
                        ? SAVE_FAILED + " The Detailed overlay was removed for this connection only."
                        : SAVE_FAILED));
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
        String saved = sessionOnly ? display(quality) + " chosen for this connection only. " + SESSION_ONLY_NOTE
                : "Saved: " + display(quality) + ".";
        if (identities.bedrock(player)) {
            return saved + BEDROCK_SAVED_TAIL;
        }
        if (!live) {
            return saved + " Applies on your next connection.";
        }
        if (quality == Quality.STANDARD) {
            return saved + " The Detailed overlay is removed for this connection.";
        }
        return saved + switch (packs.overlay(player)) {
            case PENDING -> " Requesting the Detailed overlay now. If you decline it, Standard stays active.";
            case LOADED -> " It is already active.";
            case NONE -> " It will be requested once Standard has loaded.";
            default -> sessionOnly
                    ? " This connection already used its one overlay request, so Detailed cannot load until you"
                            + " reconnect and choose it again."
                    : " This connection already used its one overlay request, so Detailed applies on your next"
                            + " connection.";
        };
    }

    /**
     * Admin recovery: durably saves Standard for an identity, online or not, and removes the
     * overlay if that Java player is connected.
     */
    public void reset(PlayerKey key, Consumer<Result> done) {
        store.save(key, Quality.STANDARD).whenComplete((stored, failure) -> mainThread.execute(() -> {
            if (failure != null) {
                done.accept(new Result(false, SAVE_FAILED));
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
                notifier.tell(account.get(), "An administrator reset your texture quality to Standard.");
            }
            done.accept(new Result(true, "Reset " + key + " to Standard."
                    + (removed ? " Their Detailed overlay was removed." : "")));
        }));
    }
}
