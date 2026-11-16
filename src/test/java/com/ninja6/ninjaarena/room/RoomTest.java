package com.ninja6.ninjaarena.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoomTest {

    private static final UUID HOST = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);

    private final Room room = new Room(HOST, "dojo", new RoomSettings(false, 3, 2, 0));

    @Test
    void playersAreBalancedAcrossTheTeamsAsInAWaitingRoom() {
        room.join(B);
        room.join(C);
        room.join(D);
        assertEquals(List.of(List.of(HOST, C), List.of(B, D)), room.teams());
        assertEquals(2, room.teamOf(B));

        var moves = room.leave(C, id -> true).orElseThrow();
        assertEquals(List.of(), moves);
        room.leave(HOST, id -> true);
        assertEquals(1, room.teams().get(0).size(), "a player moved across to keep the teams even");
        assertEquals(1, room.teams().get(1).size());
    }

    @Test
    void theRoomCanStartOnlyOnceTheRequiredCountIsMet() {
        assertFalse(room.canStart());
        room.join(B);
        assertFalse(room.canStart());
        room.join(C);
        assertTrue(room.canStart());
        room.leave(C, id -> true);
        assertFalse(room.canStart());
    }

    @Test
    void theHostPassesToTheLongestWaitingMemberWhoMayHost() {
        room.join(B);
        room.join(C);
        room.join(D);
        room.leave(HOST, Set.of(C, D)::contains);
        assertEquals(C, room.host(), "B waited longest but may not host");
        assertFalse(room.isOrphaned());
        assertEquals(List.of(B, C, D), room.members());
    }

    @Test
    void aRoomWhoseHostLeftWithNoOneToTakeOverIsOrphaned() {
        room.join(B);
        room.leave(HOST, id -> false);
        assertTrue(room.isOrphaned());
        assertEquals(Optional.empty(), room.leave(HOST, id -> true));
    }

    @Test
    void publicRoomsAdmitAnyoneNotRemovedAndInviteOnlyRoomsOnlyTheInvited() {
        assertTrue(room.admits(B));
        room.join(B);
        room.kick(B);
        assertFalse(room.contains(B));
        assertFalse(room.admits(B), "removed until invited again");
        room.invite(B);
        assertTrue(room.admits(B));

        room.setSettings(room.settings().withInviteOnly(true));
        assertFalse(room.admits(C));
        room.invite(C);
        assertTrue(room.admits(C));
        room.join(C);
        assertFalse(room.isInvited(C), "an invitation is used up by joining");
    }

    @Test
    void aTeamSizeCapsTheRoomAndNewSettingsDealTheTeamsAgain() {
        room.setSettings(new RoomSettings(false, 2, 2, 1));
        room.join(B);
        assertTrue(room.isFull());
        assertThrows(IllegalStateException.class, () -> room.join(C));

        assertThrows(IllegalArgumentException.class, () -> room.setSettings(new RoomSettings(false, 1, 1, 1)));
        room.setSettings(new RoomSettings(false, 2, 1, 0));
        assertEquals(List.of(List.of(HOST, B)), room.teams());
    }

    @Test
    void settingsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new RoomSettings(false, 0, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new RoomSettings(false, 65, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new RoomSettings(false, 2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RoomSettings(false, 2, 2, 65));
        assertEquals(Integer.MAX_VALUE, new RoomSettings(false, 2, 2, 0).capacity());
        assertEquals(6, new RoomSettings(false, 2, 3, 2).capacity());
    }

    // ---- Co-hosts, the creator and the host grace ----

    @Test
    void theHostPassesToTheLongestWaitingCohostWhoMayHostBeforeAnyMember() {
        room.join(B);
        room.join(C);
        room.join(D);
        assertTrue(room.addCohost(D));
        assertTrue(room.addCohost(C));
        assertEquals(List.of(C, D), room.cohosts(), "in join order");
        room.leave(HOST, Set.of(B, D)::contains);
        assertEquals(D, room.host(), "C waited longer but may not host; B is no co-host");
        assertFalse(room.isCohost(D), "the new host is a co-host no more");
        assertEquals(List.of(C), room.cohosts());

        room.leave(D, Set.of(B)::contains);
        assertEquals(B, room.host(), "with no co-host who may host, the longest-waiting member who may");
        room.leave(B, id -> false);
        assertTrue(room.isOrphaned(), "nobody left may host");
    }

    @Test
    void onlyMembersOtherThanTheHostAreCohostsAndLeavingEndsIt() {
        room.join(B);
        assertThrows(IllegalArgumentException.class, () -> room.addCohost(HOST));
        assertThrows(IllegalArgumentException.class, () -> room.addCohost(C));
        assertTrue(room.addCohost(B));
        assertFalse(room.addCohost(B));
        assertTrue(room.isManager(B));
        assertTrue(room.isManager(HOST));
        room.leave(B, id -> true);
        room.join(B);
        assertFalse(room.isCohost(B), "a co-host who leaves comes back a plain member");
        assertTrue(room.addCohost(B));
        assertTrue(room.removeCohost(B));
        assertFalse(room.removeCohost(B));
        room.join(C);
        room.addCohost(C);
        room.kick(C);
        assertFalse(room.isCohost(C));
    }

    @Test
    void theCreatorStaysThroughAHandOverAndTheRoomCanBeHandedBack() {
        room.join(B);
        room.join(C);
        assertEquals(HOST, room.creator());
        assertTrue(room.hasCreator());
        room.leave(HOST, id -> true);
        assertEquals(B, room.host());
        assertEquals(HOST, room.creator(), "the creator is still the creator");
        assertFalse(room.hasCreator());
        room.join(HOST);
        assertTrue(room.hasCreator());
        assertEquals(B, room.host(), "back with the crown, not the host role");

        room.handTo(HOST);
        assertEquals(HOST, room.host());
        assertTrue(room.isCohost(B), "the host before stays a co-host");
        assertThrows(IllegalArgumentException.class, () -> room.handTo(HOST));
        assertThrows(IllegalArgumentException.class, () -> room.handTo(D));
    }

    @Test
    void theHostGraceCountsDownAndEndsWhenTheHostLeaves() {
        room.join(B);
        assertThrows(IllegalArgumentException.class, () -> room.holdHost(0));
        assertFalse(room.isHostAway());
        room.holdHost(2);
        assertTrue(room.isHostAway());
        assertEquals(2, room.hostGraceLeft());
        assertFalse(room.countDownGrace());
        assertTrue(room.countDownGrace(), "run out");
        assertFalse(room.isHostAway());
        assertFalse(room.countDownGrace());
        assertEquals(HOST, room.host(), "running out takes nobody out; the service does");

        room.holdHost(5);
        room.endGrace();
        assertFalse(room.isHostAway());
        room.holdHost(5);
        room.leave(HOST, id -> true);
        assertFalse(room.isHostAway(), "a new host is not away");
        assertEquals(B, room.host());
    }
}
