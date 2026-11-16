package com.ninja6.ninjaarena.match;

import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.PlayerRejoinMatchEvent;
import com.ninja6.ninjaarena.tool.ToolVisuals;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Shows each match player's team in the colour its tools carry ({@link ToolVisuals#teamColor}):
 * red, blue, green or yellow, on name tags, the player list and glow outlines.
 *
 * <p>Every match gets a scoreboard of its own with one team per match team. Its players,
 * eliminated ones included, and the match's spectators are shown that scoreboard from the
 * first PREP until the match ends; the main scoreboard is never changed. A viewer who stops
 * watching, leaves, quits or whose match ends gets the scoreboard they had before back,
 * unless another plugin has given them a different one meanwhile. A disconnected player held
 * for a rejoin keeps their entry, so they are coloured again when they return.
 *
 * <p>The teams allow friendly fire, as Paper checks it against the attacker's own scoreboard
 * and NinjaArena's own rule decides it; they hide invisible teammates and keep the vanilla
 * collision and name tag rules, so nothing but the colour changes.
 *
 * <p>Runs on the server thread.
 */
public final class TeamColours implements Listener {

    private static final class Board {
        final Scoreboard scoreboard;
        /** The entry each player on the board was added under: their name when added. */
        final Map<UUID, String> entries = new HashMap<>();

        Board(Scoreboard scoreboard) {
            this.scoreboard = scoreboard;
        }
    }

    /** The match board a viewer is shown, and the scoreboard to give back. */
    private record Shown(Board board, Scoreboard previous) {
    }

    private final Supplier<List<MatchView>> matches;
    private final Function<UUID, Player> players;
    private final Supplier<Scoreboard> newBoard;
    private final Supplier<Scoreboard> mainBoard;
    private final Map<MatchView, Board> boards = new IdentityHashMap<>();
    private final Map<UUID, Shown> shown = new HashMap<>();
    private Function<String, Collection<UUID>> spectators = arena -> List.of();
    private BukkitTask ticker;
    private boolean closed;

    /**
     * @param matches   the running matches
     * @param players   online players by id
     * @param newBoard  a new, empty scoreboard for each match
     * @param mainBoard the server's main scoreboard, given back to a viewer who had none
     */
    public TeamColours(Supplier<List<MatchView>> matches, Function<UUID, Player> players,
            Supplier<Scoreboard> newBoard, Supplier<Scoreboard> mainBoard) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.players = Objects.requireNonNull(players, "players");
        this.newBoard = Objects.requireNonNull(newBoard, "newBoard");
        this.mainBoard = Objects.requireNonNull(mainBoard, "mainBoard");
    }

    /** Who watches each arena's match from outside its roster; by default nobody. */
    public void setSpectators(Function<String, Collection<UUID>> spectators) {
        this.spectators = Objects.requireNonNull(spectators, "spectators");
    }

    /** Catches up once a second with spectators and any change no event announced. */
    public void start(Plugin plugin) {
        if (!closed && ticker == null) {
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
        }
    }

    public void tick() {
        if (closed) {
            return;
        }
        List<MatchView> live = matches.get();
        for (MatchView match : new ArrayList<>(boards.keySet())) {
            if (!live.contains(match)) {
                remove(match);
            }
        }
        for (MatchView match : live) {
            refresh(match);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPhase(MatchPhaseChangeEvent event) {
        if (!closed) {
            refresh(event.match());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeave(PlayerLeaveMatchEvent event) {
        Board board = boards.get(event.match());
        if (board != null) {
            removeEntry(board, event.player());
        }
        Shown viewing = shown.get(event.player());
        if (viewing != null && viewing.board() == board) {
            hide(event.player());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRejoin(PlayerRejoinMatchEvent event) {
        if (!closed) {
            refresh(event.match());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchEnd(MatchEndEvent event) {
        remove(event.match());
    }

    /** The server forgets a player's own scoreboard when they quit, so there is nothing to give back. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
    }

    /** Gives every viewer their scoreboard back. Called on plugin disable. */
    public void close() {
        closed = true;
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (MatchView match : new ArrayList<>(boards.keySet())) {
            remove(match);
        }
        for (UUID id : new ArrayList<>(shown.keySet())) {
            hide(id);
        }
    }

    /** The colour of a team's names, the same as its tools. */
    public static NamedTextColor colour(int team) {
        return Objects.requireNonNull(NamedTextColor.namedColor(ToolVisuals.teamColor(team)));
    }

    private void refresh(MatchView match) {
        if (match.phase() == MatchPhase.WAITING || match.phase() == MatchPhase.MATCH_END) {
            remove(match);
            return;
        }
        Board board = boards.computeIfAbsent(match, ignored -> new Board(newBoard.get()));
        List<UUID> roster = match.players();
        for (UUID id : new ArrayList<>(board.entries.keySet())) {
            if (!roster.contains(id)) {
                removeEntry(board, id);
            }
        }
        Set<UUID> viewers = new HashSet<>();
        for (UUID id : roster) {
            Player player = players.apply(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (!board.entries.containsKey(id)) {
                team(board, match.teamOf(id)).addEntry(player.getName());
                board.entries.put(id, player.getName());
            }
            if (!match.isDisconnected(id)) {
                viewers.add(id);
            }
        }
        viewers.addAll(spectators.apply(match.arena()));
        for (Map.Entry<UUID, Shown> viewing : new ArrayList<>(shown.entrySet())) {
            if (viewing.getValue().board() == board && !viewers.contains(viewing.getKey())) {
                hide(viewing.getKey());
            }
        }
        for (UUID id : viewers) {
            Shown viewing = shown.get(id);
            if (viewing != null && viewing.board() == board) {
                continue;
            }
            Player player = players.apply(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (viewing != null) {
                hide(id);
            }
            Scoreboard previous = player.getScoreboard();
            player.setScoreboard(board.scoreboard);
            shown.put(id, new Shown(board, ours(previous) ? null : previous));
        }
    }

    private Team team(Board board, int number) {
        String name = "team" + number;
        Team team = board.scoreboard.getTeam(name);
        if (team == null) {
            team = board.scoreboard.registerNewTeam(name);
            NamedTextColor colour = colour(number);
            team.color(colour);
            team.displayName(Component.text("Team " + number, colour));
            team.setAllowFriendlyFire(true);
            team.setCanSeeFriendlyInvisibles(false);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.ALWAYS);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        }
        return team;
    }

    private static void removeEntry(Board board, UUID id) {
        String entry = board.entries.remove(id);
        if (entry != null) {
            Team team = board.scoreboard.getEntryTeam(entry);
            if (team != null) {
                team.removeEntry(entry);
            }
        }
    }

    private void remove(MatchView match) {
        Board board = boards.remove(match);
        if (board == null) {
            return;
        }
        for (Map.Entry<UUID, Shown> viewing : new ArrayList<>(shown.entrySet())) {
            if (viewing.getValue().board() == board) {
                hide(viewing.getKey());
            }
        }
        for (UUID id : new ArrayList<>(board.entries.keySet())) {
            removeEntry(board, id);
        }
        for (Team team : new ArrayList<>(board.scoreboard.getTeams())) {
            team.unregister();
        }
    }

    private void hide(UUID id) {
        Shown viewing = shown.remove(id);
        if (viewing == null) {
            return;
        }
        Player player = players.apply(id);
        if (player != null && player.isOnline() && player.getScoreboard() == viewing.board().scoreboard) {
            player.setScoreboard(viewing.previous() != null ? viewing.previous() : mainBoard.get());
        }
    }

    private boolean ours(Scoreboard scoreboard) {
        for (Board board : boards.values()) {
            if (board.scoreboard == scoreboard) {
                return true;
            }
        }
        return false;
    }
}
