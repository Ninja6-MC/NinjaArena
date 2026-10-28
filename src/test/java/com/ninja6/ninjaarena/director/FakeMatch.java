package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchResult;
import com.ninja6.ninjaarena.match.MatchSettings;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.RoundResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A match whose state the test sets directly. */
final class FakeMatch implements MatchView {

    /** Prep 15, combat 240, overrun limit 120. */
    static final MatchSettings SETTINGS = MatchSettings.DEFAULTS;

    final List<List<UUID>> roster;
    final Set<UUID> alive = new LinkedHashSet<>();
    MatchPhase phase = MatchPhase.PREP;
    int round = 1;
    int elapsed;

    FakeMatch(List<List<UUID>> roster) {
        this.roster = roster;
        roster.forEach(alive::addAll);
    }

    /** Puts the match in COMBAT the given number of seconds into the fight. */
    FakeMatch combat(int seconds) {
        phase = MatchPhase.COMBAT;
        elapsed = SETTINGS.prepSeconds() + seconds;
        return this;
    }

    void nextRound() {
        round++;
        alive.clear();
        roster.forEach(alive::addAll);
        combat(0);
    }

    @Override
    public String arena() {
        return "dojo";
    }

    @Override
    public MatchPhase phase() {
        return phase;
    }

    @Override
    public int round() {
        return round;
    }

    @Override
    public boolean isExtraRound() {
        return false;
    }

    @Override
    public int secondsLeft() {
        return 0;
    }

    @Override
    public int roundElapsedSeconds() {
        return phase.isInRound() ? elapsed : 0;
    }

    @Override
    public MatchSettings settings() {
        return SETTINGS;
    }

    @Override
    public int teamCount() {
        return roster.size();
    }

    @Override
    public List<List<UUID>> roster() {
        return roster;
    }

    @Override
    public List<Integer> startingTeams() {
        List<Integer> teams = new ArrayList<>();
        for (int i = 0; i < roster.size(); i++) {
            if (!roster.get(i).isEmpty()) {
                teams.add(i + 1);
            }
        }
        return teams;
    }

    @Override
    public List<UUID> players() {
        return roster.stream().flatMap(List::stream).toList();
    }

    @Override
    public List<UUID> playersOf(int team) {
        return roster.get(team - 1);
    }

    @Override
    public boolean contains(UUID player) {
        return players().contains(player);
    }

    @Override
    public int teamOf(UUID player) {
        for (int i = 0; i < roster.size(); i++) {
            if (roster.get(i).contains(player)) {
                return i + 1;
            }
        }
        return 0;
    }

    @Override
    public boolean isAlive(UUID player) {
        return phase.isInRound() && alive.contains(player);
    }

    @Override
    public List<UUID> alivePlayers() {
        return phase.isInRound() ? players().stream().filter(alive::contains).toList() : List.of();
    }

    @Override
    public List<Integer> aliveTeams() {
        return alivePlayers().stream().map(this::teamOf).distinct().sorted().toList();
    }

    @Override
    public int roundsWon(int team) {
        return 0;
    }

    @Override
    public List<RoundResult> results() {
        return List.of();
    }

    @Override
    public Optional<MatchResult> result() {
        return Optional.empty();
    }
}
