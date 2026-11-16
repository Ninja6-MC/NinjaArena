package com.ninja6.ninjaarena.room;

import static com.ninja6.ninjaarena.message.Messages.msg;
import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.arena.Arena;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Builds room screens and delegates every mutation to the command services. Server thread only. */
public final class RoomMenus {
    private record Active(Room room, RoomScreen.Screens screens) { }

    private final RoomService rooms;
    private final Supplier<String> mode;
    private final RoomScreen.Screens dialog;
    private final RoomScreen.Screens chest;
    private final BiConsumer<Player, String> spectate;
    private final Consumer<String> log;
    private final Map<UUID, Active> active = new HashMap<>();
    private boolean warned;

    public RoomMenus(RoomService rooms, Supplier<String> mode, RoomScreen.Screens dialog,
            RoomScreen.Screens chest, BiConsumer<Player, String> spectate, Consumer<String> log) {
        this.rooms = rooms;
        this.mode = mode;
        this.dialog = dialog;
        this.chest = chest;
        this.spectate = spectate;
        this.log = log;
    }

    public void open(Player player) {
        List<RoomScreen.Option> options = new ArrayList<>();
        options.add(action("create", Material.CRAFTING_TABLE, this::create));
        options.add(action("list", Material.COMPASS, this::list));
        if (rooms.roomOf(player.getUniqueId()).isPresent()) {
            options.add(action("yours", Material.OAK_DOOR, this::own));
        }
        show(player, "main", List.of(text("room.screen.hint")), options, null, "close");
    }

    private void create(Player player) {
        show(player, "create", List.of(text("room.screen.create-info")),
                List.of(action("create", Material.CRAFTING_TABLE, rooms::create)), null, "cancel");
    }

    /** Opens the same list page from a lobby board or the main room screen. */
    public void list(Player player) {
        List<RoomScreen.Option> options = new ArrayList<>();
        for (Room room : rooms.rooms()) {
            UUID host = room.host();
            String label = text(room.isPlaying() ? "room.screen.watch" : "room.screen.join",
                    "host", shortName(rooms.playerName(host)), "size", room.size());
            options.add(new RoomScreen.Option(label, room.isPlaying() ? Material.SPYGLASS : Material.OAK_DOOR,
                    (p, values) -> {
                        if (rooms.hostedBy(host).orElse(null) != room) {
                            p.sendMessage(msg("room.screen.stale"));
                        } else if (room.isPlaying()) {
                            spectate.accept(p, room.arena());
                        } else {
                            rooms.accept(p, rooms.playerName(host));
                        }
                    }));
        }
        options.add(action("back", Material.ARROW, this::open));
        show(player, "list", options.size() == 1 ? List.of(text("room.list-empty")) : List.of(),
                options, null, "close");
    }

    private void own(Player player) {
        Room room = rooms.roomOf(player.getUniqueId()).orElse(null);
        if (room == null) {
            rooms.show(player);
            return;
        }
        List<RoomScreen.Option> options = new ArrayList<>();
        UUID id = player.getUniqueId();
        boolean previewing = rooms.isPreviewing(id);
        boolean host = room.host().equals(id);
        if (host) {
            options.add(action("settings", Material.COMPARATOR, this::settings));
        }
        if (room.isManager(id)) {
            options.add(action("invite", Material.PLAYER_HEAD, this::invite));
            options.add(action("kick", Material.BARRIER, this::kick));
        }
        if (host) {
            options.add(action("cohosts", Material.GOLDEN_HELMET, this::cohosts));
            if (room.hasCreator() && !room.creator().equals(id)) {
                options.add(new RoomScreen.Option(text("room.screen.handback", "player",
                        shortName(rooms.playerName(room.creator()))), Material.GOLDEN_HELMET,
                        (p, values) -> rooms.handBack(p)));
            }
        }
        if (room.isManager(id)) {
            // While the map is built or the host is away, Start only says so, as the command does.
            options.add(rooms.building(room).isPresent()
                    ? action("start-building", Material.GRAY_DYE, p -> rooms.start(p, null))
                    : room.isHostAway() ? action("start-host-away", Material.GRAY_DYE, p -> rooms.start(p, null))
                    : action("start", Material.LIME_WOOL, this::start));
        }
        if (previewing) {
            options.add(action("preview-end", Material.ENDER_EYE, rooms::preview));
        } else if (rooms.canPreview(room)) {
            options.add(action("preview", Material.SPYGLASS, rooms::preview));
        }
        options.add(action("leave", Material.OAK_DOOR, rooms::leave));
        options.add(action("back", Material.ARROW, this::open));
        List<String> lines = new ArrayList<>();
        lines.add(text("room.screen.summary", "host", rooms.playerName(room.host()),
                "arena", room.arena(), "size", room.size(), "players", room.settings().players()));
        if (room.hasCreator()) {
            lines.add(text("room.screen.creator", "player", rooms.playerName(room.creator())));
        }
        List<UUID> cohosts = room.cohosts();
        if (!cohosts.isEmpty()) {
            lines.add(text("room.screen.cohosts-line", "players",
                    String.join(", ", cohosts.stream().map(rooms::playerName).toList())));
        }
        if (room.isHostAway()) {
            lines.add(text("room.screen.host-away", "player", rooms.playerName(room.host()),
                    "seconds", room.hostGraceLeft()));
        }
        show(player, "yours", lines, options, null, "close");
    }

    /** The host makes members co-hosts, or plain members again, one press each. */
    private void cohosts(Player player) {
        List<RoomScreen.Option> options = new ArrayList<>();
        rooms.roomOf(player.getUniqueId()).ifPresent(room -> {
            for (UUID member : room.members()) {
                if (member.equals(room.host())) {
                    continue;
                }
                String name = rooms.playerName(member);
                boolean cohost = room.isCohost(member);
                options.add(new RoomScreen.Option(text(cohost ? "room.screen.uncohost-player"
                        : "room.screen.cohost-player", "player", shortName(name)),
                        cohost ? Material.GOLDEN_HELMET : Material.LEATHER_HELMET,
                        (p, values) -> {
                            if (cohost) {
                                rooms.uncohost(p, name);
                            } else {
                                rooms.cohost(p, name);
                            }
                        }));
            }
        });
        options.add(action("back", Material.ARROW, this::own));
        show(player, "cohosts", List.of(text(options.size() == 1 ? "room.screen.cohosts-empty"
                : "room.screen.cohosts-info")), options, null, "close");
    }

    private void settings(Player player) {
        List<RoomScreen.Option> options = new ArrayList<>();
        for (String key : List.of("privacy", "players", "teams", "team-size")) {
            options.add(action(key, Material.COMPARATOR, p -> setting(p, key)));
        }
        if (player.hasPermission(NinjaArenaCommand.MAP_CHANGE)) {
            options.add(action("map", Material.MAP, this::maps));
        }
        options.add(action("back", Material.ARROW, this::own));
        show(player, "settings", List.of(text("room.screen.settings-info")), options, null, "close");
    }

    private void setting(Player player, String key) {
        Room room = rooms.roomOf(player.getUniqueId()).orElse(null);
        if (room == null) {
            rooms.settings(player, null, null);
            return;
        }
        RoomSettings settings = room.settings();
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String current;
        if (key.equals("privacy")) {
            values.addAll(List.of("public", "invite"));
            labels.addAll(List.of(text("room.screen.public"), text("room.screen.private")));
            current = settings.inviteOnly() ? "invite" : "public";
        } else {
            int min = key.equals("team-size") ? 0 : 1;
            int max = key.equals("teams") ? Arena.MAX_TEAMS
                    : key.equals("players") ? RoomSettings.MAX_PLAYERS : RoomSettings.MAX_TEAM_SIZE;
            for (int i = min; i <= max; i++) {
                values.add(Integer.toString(i));
                labels.add(i == 0 ? text("room.screen.unlimited") : Integer.toString(i));
            }
            current = Integer.toString(switch (key) {
                case "players" -> settings.players();
                case "teams" -> settings.teams();
                default -> settings.teamSize();
            });
        }
        RoomScreen.Input input = new RoomScreen.Input(text("room.screen." + key), values, labels, current);
        show(player, "settings", List.of(text("room.screen.save-info")), List.of(new RoomScreen.Option(
                text("room.screen.save"), Material.LIME_WOOL, (p, submitted) -> {
                    String value = submitted.get("value");
                    if (values.contains(value)) {
                        rooms.settings(p, key, value);
                    }
                })), input, "cancel");
    }

    private void maps(Player player) {
        List<RoomScreen.Option> options = new ArrayList<>();
        for (String map : rooms.pickableMaps()) {
            options.add(new RoomScreen.Option(text("room.screen.choose-map", "map", shortName(map)), Material.MAP,
                    (p, values) -> rooms.mapSet(p, map)));
        }
        options.add(action("generated", Material.GRASS_BLOCK, p -> rooms.mapSetGenerated(p, null, null)));
        options.add(action("next", Material.ARROW, rooms::mapNext));
        options.add(action("reroll", Material.ENDER_PEARL, rooms::mapReroll));
        options.add(action("back", Material.ARROW, this::settings));
        show(player, "map", List.of(), options, null, "close");
    }

    private void invite(Player player) {
        List<RoomScreen.Option> options = new ArrayList<>();
        for (Player target : rooms.inviteCandidates(player)) {
            String name = target.getName();
            options.add(new RoomScreen.Option(text("room.screen.invite-player", "player", shortName(name)),
                    Material.PLAYER_HEAD, (p, values) -> rooms.invite(p, name)));
        }
        options.add(action("back", Material.ARROW, this::own));
        show(player, "invite", options.size() == 1 ? List.of(text("room.invite-list-empty")) : List.of(),
                options, null, "close");
    }

    private void kick(Player player) {
        List<RoomScreen.Option> options = new ArrayList<>();
        UUID id = player.getUniqueId();
        rooms.roomOf(id).ifPresent(room -> {
            boolean host = room.host().equals(id);
            for (UUID member : room.members()) {
                // A co-host removes plain members only.
                if (!member.equals(room.host()) && !member.equals(id) && (host || !room.isCohost(member))) {
                    options.add(new RoomScreen.Option(text("room.screen.kick-player", "player",
                            shortName(rooms.playerName(member))), Material.BARRIER,
                            (p, values) -> rooms.kick(p, rooms.playerName(member))));
                }
            }
        });
        options.add(action("back", Material.ARROW, this::own));
        show(player, "kick", List.of(), options, null, "close");
    }

    private void start(Player player) {
        show(player, "start", List.of(text("room.screen.start-info")), List.of(action("start", Material.LIME_WOOL,
                p -> rooms.start(p, null))), null, "cancel");
    }

    private static RoomScreen.Option action(String key, Material icon, Consumer<Player> action) {
        return new RoomScreen.Option(text("room.screen." + key), icon, (p, values) -> action.accept(p));
    }

    private void show(Player player, String title, List<String> lines, List<RoomScreen.Option> options,
            RoomScreen.Input input, String exit) {
        UUID id = player.getUniqueId();
        if (!player.hasPermission(NinjaArenaCommand.PLAY)) {
            player.sendMessage(msg("command.no-permission"));
            return;
        }
        RoomScreen screen = new RoomScreen(text("room.screen.title." + title), lines, List.copyOf(options),
                input, text("room.screen." + exit));
        RoomScreen.Screens chosen = mode.get().equalsIgnoreCase("chest") ? chest : dialog;
        Active session = new Active(rooms.roomOf(id).orElse(null), chosen);
        active.put(id, session);
        try {
            render(player, screen, session);
        } catch (RuntimeException | LinkageError failure) {
            if (chosen == chest) {
                active.remove(id, session);
                throw failure;
            }
            if (!warned) {
                warned = true;
                log.accept("Room dialogs unavailable; using chest menus. Set rooms.menu to chest. "
                        + failure.getClass().getSimpleName());
            }
            session = new Active(session.room(), chest);
            active.put(id, session);
            try {
                render(player, screen, session);
            } catch (RuntimeException | LinkageError fallbackFailure) {
                active.remove(id, session);
                throw fallbackFailure;
            }
        }
    }

    private void render(Player player, RoomScreen screen, Active session) {
        UUID owner = player.getUniqueId();
        session.screens().show(player, screen, (presser, index, submitted) -> {
            if (!presser.getUniqueId().equals(owner) || active.get(owner) != session) {
                return;
            }
            active.remove(owner);
            session.screens().close(presser);
            if (index == -1) {
                return;
            }
            if (!presser.hasPermission(NinjaArenaCommand.PLAY)) {
                presser.sendMessage(msg("command.no-permission"));
            } else if (rooms.roomOf(owner).orElse(null) != session.room()) {
                presser.sendMessage(msg("room.screen.stale"));
            } else if (index >= 0 && index < screen.options().size()) {
                screen.options().get(index).action().run(presser, submitted);
            }
        });
    }

    /** Forget callbacks when a player quits, so an old form cannot affect a later connection. */
    public void forget(UUID player) {
        active.remove(player);
    }

    public void closeAll(Function<UUID, Player> players) {
        for (var entry : Map.copyOf(active).entrySet()) {
            Player player = players.apply(entry.getKey());
            if (player != null) {
                entry.getValue().screens().close(player);
            }
        }
        active.clear();
    }

    private static String shortName(String name) {
        return name.length() <= 20 ? name : name.substring(0, 19) + "…";
    }
}
