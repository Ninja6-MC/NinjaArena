package com.ninja6.ninjaarena.resourcepack;

import com.ninja6.ninjaarena.resourcepack.ResourcePackSettings.Pack;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;

/** Connection-scoped pack delivery. Call on the server thread; no client state is inferred from acceptance. */
public final class ResourcePacks implements LoadedPacks, AutoCloseable {

    /** Adds and removes individual identities, never replacing a player's pack stack. */
    public interface Transport {
        void send(UUID player, Pack pack, boolean required);
        void remove(UUID player, UUID pack);
        void kick(UUID player, String message);
        void error(String message, RuntimeException failure);

        /** An operator-facing problem with no exception behind it. */
        default void warn(String message) {
        }
    }

    /** What became of this connection's one Detailed overlay request. */
    public enum OverlayState {
        /** Never requested on this connection. */
        NONE,
        /** Requested; the client has not reported success. */
        PENDING,
        /** The client reported that the overlay loaded. */
        LOADED,
        DECLINED,
        /** Download, reload, timeout or send failure; see {@link #overlayReason}. */
        FAILED,
        /** Removed on request, for example when Standard was chosen. */
        CANCELLED
    }

    /** Observes changes the preference layer reacts to. Called on the server thread. */
    public interface Listener {
        /** The base pack reported success for this connection. */
        void baseLoaded(UUID player);

        /** The overlay reached a new state; {@code reason} is empty unless it failed or was declined. */
        void overlay(UUID player, OverlayState state, String reason);
    }

    private enum Phase { PENDING, ACCEPTED, DOWNLOADED, LOADED, FAILED }

    private static final class Attempt {
        private final long started;
        private Phase phase = Phase.PENDING;

        private Attempt(long started) {
            this.started = started;
        }
    }

    private static final class Connection {
        private final Attempt base;
        private Attempt overlay;
        private OverlayState overlayState = OverlayState.NONE;
        private String overlayReason = "";

        private Connection(long started) {
            base = new Attempt(started);
        }
    }

    private final ResourcePackSettings settings;
    private final Transport transport;
    private final LongSupplier ticks;
    private final java.util.function.Predicate<UUID> bedrock;
    private final Map<UUID, Connection> connections = new HashMap<>();
    private Listener listener;
    private boolean closed;

    public ResourcePacks(ResourcePackSettings settings, Transport transport, LongSupplier ticks) {
        this(settings, transport, ticks, player -> false);
    }

    public ResourcePacks(ResourcePackSettings settings, Transport transport, LongSupplier ticks,
            java.util.function.Predicate<UUID> bedrock) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.ticks = java.util.Objects.requireNonNull(ticks, "ticks");
        this.bedrock = java.util.Objects.requireNonNull(bedrock, "Bedrock detector");
    }

    /** One base request per connection, even after failure. A reconnect starts a new attempt. */
    public void join(UUID player) {
        if (closed || !settings.enabled() || bedrock.test(player) || connections.containsKey(player)) {
            return;
        }
        Connection connection = new Connection(ticks.getAsLong());
        connections.put(player, connection);
        try {
            transport.send(player, settings.base(), settings.required());
        } catch (RuntimeException e) {
            transport.error("Could not send the Standard resource pack to " + player, e);
            failBase(player, connection, "could not be requested");
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Whether an overlay is configured, regardless of any connection. */
    public boolean detailedConfigured() {
        return settings.enabled() && settings.detailed().isPresent();
    }

    /** This connection's overlay state; {@link OverlayState#NONE} for an unknown player. */
    public OverlayState overlay(UUID player) {
        Connection connection = connections.get(player);
        return connection == null ? OverlayState.NONE : connection.overlayState;
    }

    public String overlayReason(UUID player) {
        Connection connection = connections.get(player);
        return connection == null ? "" : connection.overlayReason;
    }

    public void quit(UUID player) {
        connections.remove(player);
    }

    @Override
    public boolean loaded(UUID player) {
        Connection connection = connections.get(player);
        return !closed && !bedrock.test(player) && connection != null && connection.base.phase == Phase.LOADED;
    }

    /** Only statuses for this connection's requested identities affect it. */
    public void status(UUID player, UUID pack, Status status) {
        Connection connection = connections.get(player);
        if (closed || bedrock.test(player) || connection == null) {
            return;
        }
        boolean base = settings.base().id().equals(pack);
        Attempt attempt = base ? connection.base
                : settings.detailed().filter(value -> value.id().equals(pack)).isPresent() ? connection.overlay : null;
        if (attempt == null || attempt.phase == Phase.FAILED) {
            return;
        }
        switch (status) {
            case ACCEPTED -> {
                if (attempt.phase == Phase.PENDING) {
                    attempt.phase = Phase.ACCEPTED;
                }
            }
            case DOWNLOADED -> {
                if (attempt.phase != Phase.LOADED) {
                    attempt.phase = Phase.DOWNLOADED;
                }
            }
            case SUCCESSFULLY_LOADED -> {
                boolean first = attempt.phase != Phase.LOADED;
                attempt.phase = Phase.LOADED;
                if (first && base) {
                    if (listener != null) {
                        listener.baseLoaded(player);
                    }
                } else if (first) {
                    connection.overlayState = OverlayState.LOADED;
                    notifyOverlay(player, connection);
                }
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                if (base) {
                    failBase(player, connection, reason(status));
                } else {
                    endOverlay(player, connection, status == Status.DECLINED ? OverlayState.DECLINED
                            : OverlayState.FAILED, reason(status));
                }
            }
        }
    }

    /** Timeout includes download and reload time; progress never extends it indefinitely. */
    public void tick() {
        if (closed) {
            return;
        }
        long now = ticks.getAsLong();
        for (var entry : Map.copyOf(connections).entrySet()) {
            Connection connection = entry.getValue();
            if (timedOut(connection.base, now)) {
                failBase(entry.getKey(), connection, "timed out while loading");
            } else if (connection.overlay != null && timedOut(connection.overlay, now)) {
                endOverlay(entry.getKey(), connection, OverlayState.FAILED, "timed out while loading");
            }
        }
    }

    /**
     * Explicit quality selection only. Detailed is optional even when Standard is required,
     * and each connection gets one overlay request: after a decline, failure or removal this
     * returns false until the player reconnects.
     */
    public boolean addDetailed(UUID player) {
        Connection connection = connections.get(player);
        if (!loaded(player) || settings.detailed().isEmpty() || connection.overlayState != OverlayState.NONE) {
            return false;
        }
        connection.overlay = new Attempt(ticks.getAsLong());
        connection.overlayState = OverlayState.PENDING;
        try {
            transport.send(player, settings.detailed().orElseThrow(), false);
        } catch (RuntimeException e) {
            transport.error("Could not send the Detailed resource pack to " + player, e);
            endOverlay(player, connection, OverlayState.FAILED, "could not be requested");
            return false;
        }
        notifyOverlay(player, connection);
        return true;
    }

    /** Removes this plugin's overlay only. Standard and every other plugin's packs remain. */
    public void removeDetailed(UUID player) {
        Connection connection = connections.get(player);
        if (connection != null && connection.overlay != null) {
            endOverlay(player, connection, OverlayState.CANCELLED, "");
        }
    }

    /** Ends the active overlay request, removing only its identity from the client. */
    private void endOverlay(UUID player, Connection connection, OverlayState state, String reason) {
        if (connection.overlay == null) {
            return;
        }
        connection.overlay = null;
        connection.overlayState = state;
        connection.overlayReason = reason;
        remove(player, settings.detailed().orElseThrow().id());
        notifyOverlay(player, connection);
    }

    private void notifyOverlay(UUID player, Connection connection) {
        if (listener != null) {
            listener.overlay(player, connection.overlayState, connection.overlayReason);
        }
    }

    private boolean timedOut(Attempt attempt, long now) {
        return attempt.phase != Phase.LOADED && attempt.phase != Phase.FAILED
                && now - attempt.started >= settings.timeoutSeconds() * 20L;
    }

    private void failBase(UUID player, Connection connection, String reason) {
        if (connection.base.phase == Phase.FAILED) {
            return;
        }
        connection.base.phase = Phase.FAILED;
        removeDetailed(player);
        remove(player, settings.base().id());
        if (settings.required()) {
            if (!reason.equals(reason(Status.DECLINED))) {
                transport.warn("The required resource pack " + reason + " for " + player
                        + ". Check resource-pack.url and resource-pack.sha1; players cannot join until it loads.");
            }
            transport.kick(player, "NinjaArena's required resource pack " + reason
                    + ". Check Server Resource Packs in this server's settings, then reconnect."
                    + " If it still fails, contact the server operator.");
        }
    }

    private void remove(UUID player, UUID pack) {
        try {
            transport.remove(player, pack);
        } catch (RuntimeException e) {
            transport.error("Could not remove resource pack " + pack + " from " + player, e);
        }
    }

    private static String reason(Status status) {
        return switch (status) {
            case DECLINED -> "was declined";
            case FAILED_DOWNLOAD -> "could not be downloaded";
            case INVALID_URL -> "has an invalid download URL";
            case FAILED_RELOAD -> "could not be reloaded";
            case DISCARDED -> "was discarded";
            default -> throw new IllegalArgumentException("Not a failure status: " + status);
        };
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (var entry : Map.copyOf(connections).entrySet()) {
            removeDetailed(entry.getKey());
            if (entry.getValue().base.phase != Phase.FAILED) {
                remove(entry.getKey(), settings.base().id());
            }
        }
        connections.clear();
    }
}
