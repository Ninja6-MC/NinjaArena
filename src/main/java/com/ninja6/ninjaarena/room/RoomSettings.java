package com.ninja6.ninjaarena.room;

/**
 * What a host chooses for their room.
 *
 * @param privacy    whether joining is public, by request, or by invitation
 * @param players    players needed before the match can start, 1 to {@link #MAX_PLAYERS}
 * @param teams      teams in the match, 1 to the team spawns of the room's arena
 * @param teamSize   the most players on one team, 1 to {@link #MAX_TEAM_SIZE}, or 0 for no limit
 */
public record RoomSettings(Privacy privacy, int players, int teams, int teamSize) {

    public enum Privacy { PUBLIC, ASK, INVITE }

    /** Compatibility constructor for public and invite-only rooms. */
    public RoomSettings(boolean inviteOnly, int players, int teams, int teamSize) {
        this(inviteOnly ? Privacy.INVITE : Privacy.PUBLIC, players, teams, teamSize);
    }

    public boolean inviteOnly() { return privacy == Privacy.INVITE; }

    public boolean asksToJoin() { return privacy == Privacy.ASK; }

    public static final int MAX_PLAYERS = 64;
    public static final int MAX_TEAM_SIZE = 64;

    public RoomSettings {
        java.util.Objects.requireNonNull(privacy, "privacy");
        if (players < 1 || players > MAX_PLAYERS) {
            throw new IllegalArgumentException("players must be from 1 to " + MAX_PLAYERS);
        }
        if (teams < 1) {
            throw new IllegalArgumentException("teams must be at least 1");
        }
        if (teamSize < 0 || teamSize > MAX_TEAM_SIZE) {
            throw new IllegalArgumentException("teamSize must be from 0 to " + MAX_TEAM_SIZE);
        }
    }

    /** The most players the room takes: the team size times the team count, or no limit. */
    public int capacity() {
        return teamSize == 0 ? Integer.MAX_VALUE : teams * teamSize;
    }

    public RoomSettings withInviteOnly(boolean inviteOnly) {
        return withPrivacy(inviteOnly ? Privacy.INVITE : Privacy.PUBLIC);
    }

    public RoomSettings withPrivacy(Privacy privacy) {
        return new RoomSettings(privacy, players, teams, teamSize);
    }

    public RoomSettings withPlayers(int players) {
        return new RoomSettings(privacy, players, teams, teamSize);
    }

    public RoomSettings withTeams(int teams) {
        return new RoomSettings(privacy, players, teams, teamSize);
    }

    public RoomSettings withTeamSize(int teamSize) {
        return new RoomSettings(privacy, players, teams, teamSize);
    }
}
