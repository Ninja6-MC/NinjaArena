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
}
