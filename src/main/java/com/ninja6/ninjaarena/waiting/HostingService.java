package com.ninja6.ninjaarena.waiting;

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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
    private final Predicate<UUID> inMatch;
    private final Map<UUID, Open> hosted = new HashMap<>();
    private final Map<String, Open> reserved = new HashMap<>();
    private final Map<UUID, Pending> entering = new HashMap<>();
    private boolean closed;

    public HostingService(Plugin plugin, ArenaRegistry arenas, WaitingRoomService rooms, PortalEntry entry,
            ArenaAdmission admission, Supplier<World> world, Predicate<UUID> inMatch) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.rooms = rooms;
        this.entry = entry;
        this.admission = admission;
        this.world = world;
        this.inMatch = inMatch;
        rooms.setParty(this::party);
        rooms.onDeparture(this::departed);
        rooms.onClose(this::cancelArena);
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
            tell(player, "You already host arena " + hosted.get(id).arena + ". Walk in or use /narena leave to cancel.");
            return;
        }
        String name = Arena.normalizeName(arenaName);
        if (!arenas.isAvailable(name) || reserved.containsKey(name) || rooms.hasRoom(name)) {
            tell(player, "Arena " + name + " is not free and ready for hosting.");
            return;
        }
        if (rooms.isWaiting(id) || inMatch.test(id)) {
            tell(player, "Leave your current waiting room or match before hosting.");
            return;
        }
        Open open = new Open(id, name);
        hosted.put(id, open);
        reserved.put(name, open);
        tell(player, "You opened arena " + name + ". Walk through its portal to join the Arena, then invite players"
                + " with /narena invite <player>. Hosting does not move you.");
    }

    @Override
    public void invite(Player player, String invitee) {
        prune();
        if (!eligible(player, NinjaArenaCommand.HOST)) {
            return;
        }
        Open open = hosted.get(player.getUniqueId());
        if (open == null) {
            tell(player, "Open a free arena with /narena host <arena> first.");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(invitee);
        if (target == null || !target.isOnline()) {
            tell(player, "No online player called " + invitee + ".");
            return;
        }
        if (target.getUniqueId().equals(open.host)) {
            tell(player, "You cannot invite yourself.");
            return;
        }
        if (!target.hasPermission(NinjaArenaCommand.PLAY) || inMatch.test(target.getUniqueId())
                || hosted.containsKey(target.getUniqueId())) {
            tell(player, "That player cannot accept an invitation right now.");
            return;
        }
        if (open.accepted.contains(target.getUniqueId())) {
            tell(player, "That player has already accepted your invitation.");
            return;
        }
        open.invited.add(target.getUniqueId());
        tell(target, player.getName() + " invited you to the Arena in " + open.arena + ". To join, type /narena accept "
                + player.getName() + ". You will move only if you accept.");
        tell(player, "Invited " + target.getName() + " to arena " + open.arena + ".");
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
            tell(player, "You have no open invitation from " + hostName + ".");
            return;
        }
        if (inMatch.test(id) || hosted.containsKey(id)) {
            tell(player, "Leave your current match or hosted arena before accepting.");
            return;
        }
        if (rooms.isWaiting(id)) {
            acceptWaiting(player, open);
            return;
        }
        if (entering.containsKey(id)) {
            tell(player, "Your accepted entry is already loading. Please wait.");
            return;
        }
        var refusal = entry.check(player, open.arena);
        if (refusal.isPresent()) {
            tell(player, refusal.get().message());
            return;
        }
        Pending pending = new Pending(open);
        entering.put(id, pending);
        var point = entry.waitingRoom(open.arena).orElseThrow();
        try {
            world.get().getChunkAtAsync(Math.floorDiv(point.blockX(), 16), Math.floorDiv(point.blockZ(), 16))
                    .whenComplete((chunk, error) -> {
                        if (!closed && plugin.isEnabled()) {
                            plugin.getServer().getScheduler().runTask(plugin, () -> finish(id, pending, error));
                        }
                    });
        } catch (RuntimeException e) {
            entering.remove(id, pending);
            tell(player, "The Arena could not load. Your invitation is still open; try accepting again.");
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
            tell(player, "The Arena could not load. Your invitation is still open; try accepting again.");
            return;
        }
        // The room's party supplier sees the acceptance during the synchronous join. Failure rolls it back.
        open.accepted.add(id);
        try {
            admission.enter(player, open.arena, message -> tell(player, message));
        } finally {
            if (rooms.arenaOf(id).filter(open.arena::equals).isPresent()) {
                open.invited.remove(id);
                tell(player, "You accepted the invitation to arena " + open.arena + ".");
            } else {
                open.accepted.remove(id);
            }
        }
    }

    private void acceptWaiting(Player player, Open open) {
        UUID id = player.getUniqueId();
        if (!rooms.arenaOf(id).orElseThrow().equals(open.arena)) {
            tell(player, "Leave your current waiting room before accepting.");
            return;
        }
        entering.remove(id);
        open.accepted.add(id);
        open.invited.remove(id);
        rooms.refreshParty(open.arena);
        tell(player, "You accepted the invitation to arena " + open.arena + ".");
    }

    /** Also cancels a reservation when its host has not entered the room yet. */
    public boolean leave(Player player) {
        boolean cancelled = entering.remove(player.getUniqueId()) != null;
        Open open = hosted.get(player.getUniqueId());
        if (open == null) {
            if (cancelled) {
                tell(player, "Your entry to the Arena was cancelled. Your invitation is still open.");
            }
            return cancelled;
        }
        cancelArena(open.arena);
        tell(player, "You stopped hosting arena " + open.arena + ".");
        return true;
    }

    private boolean eligible(Player player, String permission) {
        if (closed || !player.isOnline() || !player.hasPermission(permission)) {
            tell(player, "You do not have permission to use this Arena command.");
            return false;
        }
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            tell(player, "You cannot use this Arena command while dead or spectating.");
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
                    || !arenas.isAvailable(open.arena)
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

    private static void tell(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.YELLOW));
    }
}
