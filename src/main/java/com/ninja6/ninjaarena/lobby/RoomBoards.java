package com.ninja6.ninjaarena.lobby;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/** Saved layout definitions and disposable entities for the lobby's two room boards. Main thread only. */
public final class RoomBoards implements Listener, AutoCloseable {
    static final NamespacedKey KEY = new NamespacedKey("ninjaarena", "room_board");
    private static final List<String> IDS = List.of("create-room", "room-list");
    private record Board(ArenaPoint point, TextDisplay text, Interaction target) { }
    private final World world;
    private final Supplier<LobbyLayout> layout;
    private final Supplier<Component> list;
    private final BiConsumer<String, Player> open;
    private final BiConsumer<Location, Runnable> load;
    private final BoardTickets tickets;
    private final Path file;
    private final Consumer<String> log;
    private final Set<String> enabled;
    private final Map<String, Board> boards = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    private final Map<String, ArenaPoint> wanted = new HashMap<>();
    private int generation;
    private boolean closed;

    public RoomBoards(World world, Supplier<LobbyLayout> layout, Supplier<Component> list,
            BiConsumer<String, Player> open, BiConsumer<Location, Runnable> load,
            BoardTickets tickets, Path file, Set<String> enabled, Consumer<String> log) {
        this.world = world;
        this.layout = layout;
        this.list = list;
        this.open = open;
        this.load = load;
        this.tickets = tickets;
        this.file = file;
        this.enabled = Set.copyOf(enabled);
        this.log = log;
    }

    /** The layout is authoritative, so a reset moves saved definitions with the new build. */
    public void place() {
        if (closed) { return; }
        int epoch = ++generation;
        removeBoards();
        wanted.clear();
        for (String id : IDS) {
            if (!enabled.contains(id)) { continue; }
            ArenaPoint point = layout.get().boards().get(id);
            if (point == null) {
                if (warned.add(id)) { log.accept("Lobby has no " + id + " board spot; skipping it."); }
            } else {
                wanted.put(id, point);
            }
        }
        save();
        for (var entry : Map.copyOf(wanted).entrySet()) {
            String id = entry.getKey();
            ArenaPoint point = entry.getValue();
            load.accept(location(point), () -> {
                if (!closed && generation == epoch) {
                    spawn(id, point);
                }
            });
        }
    }

    private void save() {
        YamlConfiguration saved = new YamlConfiguration();
        wanted.forEach((id, point) -> {
            saved.set("boards." + id + ".spot", List.of(point.x(), point.y(), point.z()));
            saved.set("boards." + id + ".facing", point.yaw());
        });
        try {
            saved.save(file.toFile());
        } catch (IOException failure) {
            log.accept("Could not save lobby board definitions: " + failure.getMessage());
        }
    }

    private Location location(ArenaPoint point) {
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    private void spawn(String id, ArenaPoint point) {
        Location at = location(point);
        tickets.acquire(at.getBlockX() >> 4, at.getBlockZ() >> 4);
        TextDisplay text = world.spawn(at, TextDisplay.class, display -> {
            mark(display, id);
            display.text(id.equals("create-room") ? msg("lobby.boards.create") : list.get());
            display.setBillboard(Display.Billboard.FIXED);
            display.setLineWidth(500);
        });
        Interaction target = world.spawn(at.clone().subtract(0, 1, 0), Interaction.class, entity -> {
            mark(entity, id);
            entity.setInteractionWidth(3);
            entity.setInteractionHeight(2);
            entity.setResponsive(true);
        });
        boards.put(id, new Board(point, text, target));
    }

    private static void mark(Entity entity, String id) {
        entity.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
        entity.setPersistent(false);
        entity.setInvulnerable(true);
        entity.setGravity(false);
    }

    private String id(Entity entity) {
        return entity.getWorld() == world
                ? entity.getPersistentDataContainer().get(KEY, PersistentDataType.STRING) : null;
    }

    /** Rebuild a missing half and sweep tagged duplicates without disturbing other entities. */
    public void update() {
        if (closed) { return; }
        for (var entry : Map.copyOf(boards).entrySet()) {
            Board board = entry.getValue();
            if (!board.text().isValid() || !board.target().isValid()) {
                remove(entry.getKey(), board);
                spawn(entry.getKey(), board.point());
            }
        }
        for (Entity entity : world.getEntities()) { removeDuplicate(entity); }
        Board create = boards.get("create-room");
        if (create != null) { create.text().text(msg("lobby.boards.create")); }
        Board roomList = boards.get("room-list");
        if (roomList != null) { roomList.text().text(list.get()); }
    }

    private void removeDuplicate(Entity entity) {
        if (id(entity) == null) { return; }
        Board board = boards.get(id(entity));
        if (board == null || entity != board.text() && entity != board.target()) { entity.remove(); }
    }

    @EventHandler public void onChunkLoad(ChunkLoadEvent event) {
        if (event.getWorld() == world) {
            for (Entity entity : event.getChunk().getEntities()) { removeDuplicate(entity); }
        }
    }

    @EventHandler public void onWorldLoad(WorldLoadEvent event) {
        if (event.getWorld() == world) { place(); }
    }

    private void click(Entity entity, Player player) {
        String id = id(entity);
        if (id == null || !boards.containsKey(id)) { return; }
        if (!player.hasPermission(NinjaArenaCommand.PLAY)) {
            player.sendMessage(msg("command.no-permission"));
        } else {
            open.accept(id, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEntityEvent event) {
        if (!(event instanceof PlayerInteractAtEntityEvent) && id(event.getRightClicked()) != null) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND) { click(event.getRightClicked(), event.getPlayer()); }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (id(event.getAttacked()) != null) {
            event.setCancelled(true);
            click(event.getAttacked(), event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (id(event.getEntity()) != null) { event.setCancelled(true); }
    }

    private void remove(String id, Board board) {
        board.text().remove();
        board.target().remove();
        Location at = location(board.point());
        tickets.release(at.getBlockX() >> 4, at.getBlockZ() >> 4);
        boards.remove(id);
    }

    private void removeBoards() {
        for (var entry : Map.copyOf(boards).entrySet()) { remove(entry.getKey(), entry.getValue()); }
        for (Entity entity : world.getEntities()) {
            if (id(entity) != null) { entity.remove(); }
        }
    }

    @Override public void close() {
        closed = true;
        generation++;
        removeBoards();
    }
}
