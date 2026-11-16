package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.PlayerRejoinMatchEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.tool.ToolVisuals;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Team colours from the match start to every way out: leaving, a disconnect with and
 * without a rejoin, elimination, the shop, the match end, spectators and plugin disable.
 */
class TeamColoursTest extends MatchFixture {

    private static final UUID ERIN = new UUID(0, 5);

    /** A scoreboard's state, behind a proxy. */
    private static final class Board {
        final String label;
        final Map<String, TeamState> teams = new LinkedHashMap<>();
        Scoreboard proxy;

        Board(String label) {
            this.label = label;
        }
    }

    private static final class TeamState {
        final String name;
        final Set<String> entries = new LinkedHashSet<>();
        NamedTextColor colour;
        boolean friendlyFire;
        boolean seeInvisibles = true;
        final Map<Team.Option, Team.OptionStatus> options = new LinkedHashMap<>();
        boolean registered = true;

        TeamState(String name) {
            this.name = name;
        }
    }

    private final Board main = board("main");
    private final List<Board> created = new ArrayList<>();
    private final Set<UUID> watching = new HashSet<>();
    private TeamColours colours;

    @BeforeEach
    void setUpColours() {
        add(ERIN, "Erin");
        players.values().forEach(fake -> fake.scoreboard = main.proxy);
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, event -> {
            events.add(event);
            switch (event) {
                case MatchPhaseChangeEvent phase -> colours.onPhase(phase);
                case PlayerLeaveMatchEvent leave -> colours.onLeave(leave);
                case PlayerRejoinMatchEvent back -> colours.onRejoin(back);
                case MatchEndEvent end -> colours.onMatchEnd(end);
                default -> { }
            }
        }, player -> readied.add(player.getUniqueId()));
        colours = new TeamColours(matches::matches, plugin.getServer()::getPlayer, () -> {
            Board board = board("match " + (created.size() + 1));
            created.add(board);
            return board.proxy;
        }, () -> main.proxy);
        colours.setSpectators(arena -> List.copyOf(watching));
    }

    private Board board(String label) {
        Board board = new Board(label);
        board.proxy = (Scoreboard) Proxy.newProxyInstance(Scoreboard.class.getClassLoader(),
                new Class<?>[] {Scoreboard.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getTeam" -> {
                        TeamState team = board.teams.get((String) args[0]);
                        yield team == null ? null : team(board, team);
                    }
                    case "registerNewTeam" -> {
                        String name = (String) args[0];
                        if (board.teams.containsKey(name)) {
                            throw new IllegalArgumentException("team " + name + " already exists");
                        }
                        TeamState team = new TeamState(name);
                        board.teams.put(name, team);
                        yield team(board, team);
                    }
                    case "getEntryTeam" -> board.teams.values().stream()
                            .filter(team -> team.entries.contains((String) args[0])).findFirst()
                            .map(team -> team(board, team)).orElse(null);
                    case "getTeams" -> board.teams.values().stream().map(team -> team(board, team))
                            .collect(java.util.stream.Collectors.toSet());
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> board.label;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return board;
    }

    private static Team team(Board board, TeamState state) {
        return (Team) Proxy.newProxyInstance(Team.class.getClassLoader(), new Class<?>[] {Team.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> state.name;
                    case "color" -> {
                        if (args == null) {
                            yield state.colour;
                        }
                        state.colour = (NamedTextColor) args[0];
                        yield null;
                    }
                    case "displayName" -> null;
                    case "setAllowFriendlyFire" -> { state.friendlyFire = (Boolean) args[0]; yield null; }
                    case "setCanSeeFriendlyInvisibles" -> { state.seeInvisibles = (Boolean) args[0]; yield null; }
                    case "setOption" -> {
                        state.options.put((Team.Option) args[0], (Team.OptionStatus) args[1]);
                        yield null;
                    }
                    case "addEntry" -> { state.entries.add((String) args[0]); yield null; }
                    case "removeEntry" -> state.entries.remove((String) args[0]);
                    case "unregister" -> {
                        state.registered = false;
                        board.teams.remove(state.name);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(state);
                    case "equals" -> args[0] instanceof Team other && other.getName().equals(state.name);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Board only() {
        assertEquals(1, created.size());
        return created.getFirst();
    }

    private Scoreboard boardOf(UUID id) {
        return players.get(id).scoreboard;
    }

    private Set<String> entries(Board board, int team) {
        TeamState state = board.teams.get("team" + team);
        return state == null ? Set.of() : state.entries;
    }

    private void assertColoured(UUID... ids) {
        Board board = only();
        for (UUID id : ids) {
            assertSame(board.proxy, boardOf(id), players.get(id).name + " sees the match board");
        }
    }

    private void assertUncoloured(UUID... ids) {
        for (UUID id : ids) {
            assertSame(main.proxy, boardOf(id), players.get(id).name + " has the main scoreboard back");
        }
    }

    private void assertMainUntouched() {
        assertTrue(main.teams.isEmpty(), "nothing is ever registered on the main scoreboard");
    }

    @Test
    void theMatchStartPutsEachTeamInItsToolColourOnItsOwnBoard() {
        startTwoOnTwo();
        Board board = only();
        assertColoured(ALICE, BOB, CAROL, DAVE);
        assertUncoloured(ERIN);
        assertEquals(Set.of("Alice", "Carol"), entries(board, 1));
        assertEquals(Set.of("Bob", "Dave"), entries(board, 2));
        assertEquals(NamedTextColor.RED, board.teams.get("team1").colour);
        assertEquals(NamedTextColor.BLUE, board.teams.get("team2").colour);
        for (TeamState team : board.teams.values()) {
            assertTrue(team.friendlyFire, "NinjaArena's own rule decides friendly fire");
            assertFalse(team.seeInvisibles, "teammates stay invisible to each other as before");
            assertEquals(Team.OptionStatus.ALWAYS, team.options.get(Team.Option.COLLISION_RULE));
            assertEquals(Team.OptionStatus.ALWAYS, team.options.get(Team.Option.NAME_TAG_VISIBILITY));
        }
        assertMainUntouched();
    }

    @Test
    void theColoursMatchTheToolsForAllFourTeams() {
        assertEquals(List.of(NamedTextColor.RED, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.YELLOW),
                List.of(TeamColours.colour(1), TeamColours.colour(2), TeamColours.colour(3), TeamColours.colour(4)));
        for (int team = 1; team <= com.ninja6.ninjaarena.arena.Arena.MAX_TEAMS; team++) {
            assertEquals(ToolVisuals.teamColor(team), TeamColours.colour(team).value());
        }
    }

    @Test
    void leavingGivesTheScoreboardBackAndDropsTheEntry() {
        startTwoOnTwo();
        Board board = only();
        assertTrue(matches.leave(player(BOB), MatchService.Exit.COMMAND));
        assertUncoloured(BOB);
        assertEquals(Set.of("Dave"), entries(board, 2));
        assertColoured(ALICE, CAROL, DAVE);
        colours.tick();
        assertUncoloured(BOB);
        assertMainUntouched();
    }

    @Test
    void anotherPluginsScoreboardIsGivenBackAndAReplacementMidMatchIsLeftAlone() {
        Board sidebar = board("sidebar");
        Board other = board("other");
        players.get(ALICE).scoreboard = sidebar.proxy;
        startTwoOnTwo();
        assertColoured(ALICE);
        players.get(CAROL).scoreboard = other.proxy;
        colours.tick();
        assertSame(other.proxy, boardOf(CAROL), "the colours do not fight another plugin for the scoreboard");
        matches.leave(player(ALICE), MatchService.Exit.COMMAND);
        matches.leave(player(CAROL), MatchService.Exit.COMMAND);
        assertSame(sidebar.proxy, boardOf(ALICE));
        assertSame(other.proxy, boardOf(CAROL));
    }

    @Test
    void eliminatedPlayersKeepTheirColour() {
        startTwoOnTwo();
        ticks(2);
        matches.died(player(BOB), player(ALICE));
        runNextTick();
        colours.tick();
        assertFalse(matches.matchOf(BOB).orElseThrow().isAlive(BOB));
        assertColoured(ALICE, BOB, CAROL, DAVE);
        assertTrue(entries(only(), 2).contains("Bob"));
    }

    @Test
    void theShopKeepsTheColoursAndLeavingDuringItRemovesThem() {
        matches.setShopSettings(new ShopSettings(true, 30, 2, 3));
        startTwoOnTwo();
        ticks(2);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(ALICE));
        runNextTick();
        ticks(1);
        assertEquals(MatchPhase.SHOP, matches.match("dojo").orElseThrow().phase());
        colours.tick();
        assertColoured(ALICE, BOB, CAROL, DAVE);
        matches.leave(player(CAROL), MatchService.Exit.COMMAND);
        assertUncoloured(CAROL);
        assertEquals(Set.of("Alice"), entries(only(), 1));
        assertMainUntouched();
    }

    @Test
    void aDisconnectHeldForARejoinIsColouredAgainOnReturn() {
        matches.setRejoinGraceSeconds(20);
        startTwoOnTwo();
        matches.disconnect(player(BOB));
        colours.onQuit(new PlayerQuitEvent(player(BOB), (net.kyori.adventure.text.Component) null,
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        players.get(BOB).online = false;
        colours.tick();
        assertTrue(entries(only(), 2).contains("Bob"), "the held player keeps their entry");

        // The server gives a joining player the main scoreboard.
        players.get(BOB).scoreboard = main.proxy;
        players.get(BOB).online = true;
        assertTrue(matches.rejoin(player(BOB)));
        runNextTick();
        assertColoured(BOB);
        assertEquals(Set.of("Bob", "Dave"), entries(only(), 2));
    }

    @Test
    void aDisconnectPastTheGraceLeavesTheBoard() {
        matches.setRejoinGraceSeconds(1);
        startTwoOnTwo();
        matches.disconnect(player(BOB));
        players.get(BOB).online = false;
        colours.onQuit(new PlayerQuitEvent(player(BOB), (net.kyori.adventure.text.Component) null,
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        ticks(1);
        colours.tick();
        assertEquals(Set.of("Dave"), entries(only(), 2));

        players.get(BOB).scoreboard = main.proxy;
        players.get(BOB).online = true;
        assertFalse(matches.rejoin(player(BOB)));
        colours.tick();
        assertUncoloured(BOB);
    }

    @Test
    void aDisconnectWithoutAGraceLeavesAtOnce() {
        startTwoOnTwo();
        matches.disconnect(player(BOB));
        assertEquals(Set.of("Dave"), entries(only(), 2));
        assertUncoloured(BOB);
        assertMainUntouched();
    }

    @Test
    void theMatchEndGivesEveryoneTheirScoreboardBack() {
        startTwoOnTwo();
        Board board = only();
        matches.leave(player(BOB), MatchService.Exit.COMMAND);
        matches.leave(player(DAVE), MatchService.Exit.COMMAND);
        runNextTick();
        assertColoured(ALICE, CAROL);
        for (int i = 0; i < 5 && matches.match("dojo").orElseThrow().phase() != MatchPhase.MATCH_END; i++) {
            ticks(1);
        }
        assertEquals(MatchPhase.MATCH_END, matches.match("dojo").orElseThrow().phase());
        assertUncoloured(ALICE, BOB, CAROL, DAVE);
        assertTrue(board.teams.isEmpty(), "the match board's teams are unregistered");
        colours.tick();
        assertUncoloured(ALICE, CAROL);
        assertEquals(1, created.size(), "the result pause does not colour anyone again");
        ticks(5);
        runNextTick();
        colours.tick();
        assertTrue(matches.matches().isEmpty());
        assertUncoloured(ALICE, BOB, CAROL, DAVE);
        assertMainUntouched();
    }

    @Test
    void spectatorsSeeTheColoursWhileTheyWatch() {
        startTwoOnTwo();
        watching.add(ERIN);
        colours.tick();
        assertColoured(ERIN);
        watching.remove(ERIN);
        colours.tick();
        assertUncoloured(ERIN);
        assertFalse(entries(only(), 1).contains("Erin"), "spectators are on no team");
    }

    @Test
    void disableGivesEveryoneTheirScoreboardBackAndStaysQuiet() {
        startTwoOnTwo();
        watching.add(ERIN);
        colours.tick();
        colours.close();
        assertUncoloured(ALICE, BOB, CAROL, DAVE, ERIN);
        assertTrue(only().teams.isEmpty());
        matches.close();
        colours.tick();
        assertUncoloured(ALICE, BOB, CAROL, DAVE, ERIN);
        assertMainUntouched();
    }

    @Test
    void aNewMatchAfterARestartStartsOnAFreshBoard() {
        startTwoOnTwo();
        colours.close();
        matches.close();
        runNextTick();
        colours = new TeamColours(matches::matches, plugin.getServer()::getPlayer, () -> {
            Board board = board("match " + (created.size() + 1));
            created.add(board);
            return board.proxy;
        }, () -> main.proxy);
        startTwoOnTwo();
        assertEquals(2, created.size());
        assertNotSame(created.getFirst().proxy, boardOf(ALICE));
        assertSame(created.getLast().proxy, boardOf(ALICE));
        assertNull(created.getFirst().teams.get("team1"));
        assertMainUntouched();
    }
}
