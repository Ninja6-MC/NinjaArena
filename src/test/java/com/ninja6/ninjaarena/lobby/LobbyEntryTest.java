package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class LobbyEntryTest extends LobbyFixture {

    private final LobbyMembers members = members();
    private final List<UUID> captured = new ArrayList<>();
    private final List<UUID> discarded = new ArrayList<>();
    private CaptureResult capture = CaptureResult.SAVED;
    private final LobbyEntry entry = new LobbyEntry(members, busyElsewhere::contains, player -> {
        captured.add(player.getUniqueId());
        return capture;
    }, discarded::add);

    @Test
    void aPlayerFromOutsideHasTheirSnapshotSavedAndBecomesAMember() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        assertEquals(Optional.empty(), entry.join(alice));
        assertEquals(List.of(ALICE), captured);
        assertTrue(entry.isMember(ALICE));
        assertEquals(List.of(ALICE), entry.members());

        assertEquals(Optional.empty(), entry.join(alice), "a member stays as they are");
        assertEquals(List.of(ALICE), captured, "no second snapshot");
    }

    @Test
    void aBusyPlayerOrOneWhoseSnapshotCannotBeSavedIsTurnedAway() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        busyElsewhere.add(ALICE);
        assertEquals(Optional.of("Leave your match, waiting room or spectating first."), entry.join(alice));
        assertEquals(List.of(), captured);

        busyElsewhere.clear();
        capture = CaptureResult.ALREADY_PENDING;
        assertTrue(entry.join(alice).orElseThrow().contains("have not been returned yet"));
        capture = CaptureResult.WRITE_FAILED;
        assertTrue(entry.join(alice).isPresent());
        capture = CaptureResult.PLUGIN_WORLD;
        assertTrue(entry.join(alice).isPresent());
        assertFalse(entry.isMember(ALICE));
        assertEquals(List.of(), discarded, "nothing was saved, so nothing is deleted");
    }

    @Test
    void aRefusedTeleportDeletesTheNewSnapshot() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        players.get(ALICE).refuseTeleport = true;
        assertTrue(entry.join(alice).isPresent());
        assertEquals(List.of(ALICE), discarded);
        assertFalse(entry.isMember(ALICE));
    }

    @Test
    void aPlayerBackFromAMatchEntersWithTheirSnapshotKept() {
        Player alice = add(ALICE, "Alice", new Location(arenaWorld, 5, 70, 5));
        assertTrue(entry.enter(alice));
        assertEquals(List.of(), captured);
        assertTrue(entry.isMember(ALICE));
        assertTrue(entry.enter(alice), "already a member");
    }
}
