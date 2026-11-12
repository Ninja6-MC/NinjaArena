package com.ninja6.ninjaarena.waiting;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.command.HostingCommands;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.portal.ArenaAdmission;
import com.ninja6.ninjaarena.portal.PortalEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** In-memory reservations and invitations. Only accepting an invitation can teleport a player. */
public final class HostingService implements HostingCommands, Listener {

    private static final class Open {
        final UUID host;
        final String arena;
        final Set<UUID> invited = new LinkedHashSet<>();
        final Set<UUID> accepted = new LinkedHashSet<>();

        Open(UUID host, String arena) {
            this.host = host;
            this.arena = arena;
        }
    }

    private record Pending(Open open) {
    }

    private final Plugin plugin;
    private final ArenaRegistry arenas;
    private final WaitingRoomService rooms;
    private final PortalEntry entry;
    private final ArenaAdmission admission;
    private final Supplier<World> world;
    private final Supplier<Runnable> acquireWorld;
    private final Predicate<UUID> inMatch;
    private final Map<UUID, Open> hosted = new HashMap<>();
    private final Map<String, Open> reserved = new HashMap<>();
    private final Map<UUID, Pending> entering = new HashMap<>();
    private boolean closed;

    public HostingService(Plugin plugin, ArenaRegistry arenas, WaitingRoomService rooms, PortalEntry entry,
            ArenaAdmission admission, Supplier<World> world, Predicate<UUID> inMatch) {
        this(plugin, arenas, rooms, entry, admission, world, inMatch, () -> () -> { });
    }

    public HostingService(Plugin plugin, ArenaRegistry arenas, WaitingRoomService rooms, PortalEntry entry,
            ArenaAdmission admission, Supplier<World> world, Predicate<UUID> inMatch, Supplier<Runnable> acquireWorld) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.rooms = rooms;
        this.entry = entry;
        this.admission = admission;
        this.world = world;
        this.acquireWorld = acquireWorld;
        this.inMatch = inMatch;
        rooms.setParty(this::party);
        rooms.onDeparture(this::departed);
        rooms.onClose(this::cancelArena);
        // A reserved arena waits for its host's party, so the shared hub must not take it.
        rooms.setClaimed(reserved::containsKey);
        arenas.onEditOrDelete(this::cancelArena);
    }

    @Override
    public void host(Player player, String arenaName) {
        prune();
        if (!eligible(player, NinjaArenaCommand.HOST)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (hosted.containsKey(id)) {
            tell(player, "hosting.already-hosting", "arena", hosted.get(id).arena);
            return;
        }
        String name = Arena.normalizeName(arenaName);
        if (!arenas.isAvailable(name) || reserved.containsKey(name) || rooms.hasRoom(name)) {
            tell(player, "hosting.not-free", "arena", name);
            return;
        }
        if (rooms.isWaiting(id) || inMatch.test(id)) {
            tell(player, "hosting.leave-first");
            return;
        }
        Open open = new Open(id, name);
        hosted.put(id, open);
        reserved.put(name, open);
        tell(player, "hosting.opened", "arena", name);
    }

    @Override
    public void invite(Player player, String invitee) {
        prune();
        if (!eligible(player, NinjaArenaCommand.HOST)) {
            return;
        }
        Open open = hosted.get(player.getUniqueId());
        if (open == null) {
            tell(player, "hosting.host-first");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(invitee);
        if (target == null || !target.isOnline()) {
            tell(player, "hosting.no-player", "player", invitee);
            return;
        }
        if (target.getUniqueId().equals(open.host)) {
            tell(player, "hosting.self");
            return;
        }
        if (!target.hasPermission(NinjaArenaCommand.PLAY) || inMatch.test(target.getUniqueId())
                || hosted.containsKey(target.getUniqueId())) {
            tell(player, "hosting.cannot-accept");
            return;
        }
        if (open.accepted.contains(target.getUniqueId())) {
            tell(player, "hosting.already-accepted");
            return;
        }
        open.invited.add(target.getUniqueId());
        tell(target, "hosting.invited-you", "host", player.getName(), "arena", open.arena);
        tell(player, "hosting.invited", "player", target.getName(), "arena", open.arena);
    }

    @Override
    public void accept(Player player, String hostName) {
        prune();
        if (!eligible(player, NinjaArenaCommand.PLAY)) {
            return;
        }
        Player host = plugin.getServer().getPlayerExact(hostName);
        Open open = host == null ? null : hosted.get(host.getUniqueId());
        UUID id = player.getUniqueId();
        if (open == null || !open.invited.contains(id)) {
            tell(player, "hosting.no-invitation", "host", hostName);
            return;
        }
        if (inMatch.test(id) || hosted.containsKey(id)) {
            tell(player, "hosting.leave-before-accept");
            return;
        }
        if (rooms.isWaiting(id)) {
            acceptWaiting(player, open);
            return;
        }
        if (entering.containsKey(id)) {
            tell(player, "hosting.loading");
            return;
        }
        var refusal = entry.check(player, open.arena);
        if (refusal.isPresent()) {
            tell(player, "hosting.refused", "message", refusal.get().message());
            return;
        }
        Pending pending = new Pending(open);
        entering.put(id, pending);
        var point = entry.waitingRoom(open.arena).orElseThrow();
        Runnable releaseWorld;
        try {
            releaseWorld = acquireWorld.get();
        } catch (RuntimeException e) {
            entering.remove(id, pending);
            tell(player, "hosting.load-failed");
            return;
        }
        try {
            world.get().getChunkAtAsync(Math.floorDiv(point.blockX(), 16), Math.floorDiv(point.blockZ(), 16))
                    .whenComplete((chunk, error) -> {
                        if (!closed && plugin.isEnabled()) {
                            try {
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    try {
                                        finish(id, pending, error);
                                    } finally {
                                        releaseWorld.run();
                                    }
                                });
                            } catch (RuntimeException failure) {
                                releaseWorld.run();
                                throw failure;
                            }
                        } else {
                            releaseWorld.run();
                        }
                    });
        } catch (RuntimeException e) {
            releaseWorld.run();
            entering.remove(id, pending);
            tell(player, "hosting.load-failed");
        }
    }

    private void finish(UUID id, Pending pending, Throwable error) {
        if (closed || entering.get(id) != pending) {
            return;
        }
        entering.remove(id);
        prune();
        Player player = plugin.getServer().getPlayer(id);
        Open open = pending.open();
        if (player == null || !player.isOnline() || hosted.get(open.host) != open || !open.invited.contains(id)) {
            return;
        }
        if (!eligible(player, NinjaArenaCommand.PLAY) || inMatch.test(id)
                || hosted.containsKey(id)) {
            return;
        }
        if (rooms.isWaiting(id)) {
            acceptWaiting(player, open);
            return;
        }
        if (error != null) {
            tell(player, "hosting.load-failed");
            return;
        }
        // The room's party supplier sees the acceptance during the synchronous join. Failure rolls it back.
        open.accepted.add(id);
        try {
            admission.enter(player, open.arena, message -> tell(player, "hosting.refused", "message", message));
        } finally {
            if (rooms.arenaOf(id).filter(open.arena::equals).isPresent()) {
                open.invited.remove(id);
                tell(player, "hosting.accepted", "arena", open.arena);
            } else {
                open.accepted.remove(id);
            }
        }
    }

    private void acceptWaiting(Player player, Open open) {
        UUID id = player.getUniqueId();
        if (!rooms.arenaOf(id).filter(open.arena::equals).isPresent()) {
            tell(player, "hosting.leave-room-first");
            return;
        }
        entering.remove(id);
        open.accepted.add(id);
        open.invited.remove(id);
        rooms.refreshParty(open.arena);
        tell(player, "hosting.accepted", "arena", open.arena);
    }

    /** Also cancels a reservation when its host has not entered the room yet. */
    public boolean leave(Player player) {
        boolean cancelled = entering.remove(player.getUniqueId()) != null;
        Open open = hosted.get(player.getUniqueId());
        if (open == null) {
            if (cancelled) {
                tell(player, "hosting.entry-cancelled");
            }
            return cancelled;
        }
        cancelArena(open.arena);
        tell(player, "hosting.stopped", "arena", open.arena);
        return true;
    }

    private boolean eligible(Player player, String permission) {
        if (closed || !player.isOnline() || !player.hasPermission(permission)) {
            tell(player, "hosting.no-permission");
            return false;
        }
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            tell(player, "hosting.dead");
            return false;
        }
        return true;
    }

    private List<UUID> party(String arena) {
        Open open = reserved.get(arena);
        if (open == null) {
            return List.of();
        }
        List<UUID> party = new ArrayList<>();
        party.add(open.host);
        party.addAll(open.accepted);
        return party;
    }

    private void departed(UUID player, String arena) {
        Open open = reserved.get(arena);
        if (open != null) {
            if (open.host.equals(player)) {
                cancelArena(arena);
            } else {
                open.accepted.remove(player);
            }
        }
        Open hostedElsewhere = hosted.get(player);
        if (hostedElsewhere != null) {
            cancelArena(hostedElsewhere.arena);
        }
    }

    private void cancelArena(String arena) {
        Open open = reserved.remove(arena);
        if (open != null) {
            hosted.remove(open.host);
            entering.entrySet().removeIf(entry -> entry.getValue().open() == open);
        }
    }

    private void prune() {
        for (Open open : new ArrayList<>(hosted.values())) {
            Player host = plugin.getServer().getPlayer(open.host);
            if (host == null || !host.isOnline() || !host.hasPermission(NinjaArenaCommand.HOST)
                    || !arenas.isAvailable(open.arena) || rooms.inHub(open.host)
                    || rooms.arenaOf(open.host).filter(arena -> !arena.equals(open.arena)).isPresent()) {
                cancelArena(open.arena);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        entering.remove(id);
        Open open = hosted.get(id);
        if (open != null) {
            cancelArena(open.arena);
        }
        for (Open other : hosted.values()) {
            other.invited.remove(id);
            other.accepted.remove(id);
        }
    }

    public void close() {
        closed = true;
        hosted.clear();
        reserved.clear();
        entering.clear();
    }

    private static void tell(Player player, String key, Object... placeholders) {
        player.sendMessage(msg(key, placeholders));
    }
}
