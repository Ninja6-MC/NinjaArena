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
    void lobbyAdmissionShowsTheIntroOnlyOnTheFirstArrival() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        List<Player> shown = new ArrayList<>();
        LobbyIntro intro = new LobbyIntro(() -> com.ninja6.ninjaarena.resourcepack.TextureMenuMode.DIALOG,
                shown::add, player -> members.isMember(player.getUniqueId()));
        members.setArrival(intro::arrived);

        assertEquals(Optional.empty(), entry.join(alice));
        assertTrue(entry.isMember(ALICE));
        assertEquals(List.of(ALICE), captured);
        assertEquals(List.of(alice), shown);
        assertTrue(alice.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey("ninjaarena", "intro_seen"),
                org.bukkit.persistence.PersistentDataType.BYTE));

        assertEquals(Optional.empty(), entry.join(alice), "already in the lobby");
        assertTrue(members.leave(alice, LobbyMembers.Exit.COMMAND));
        assertEquals(Optional.empty(), entry.join(alice), "arrives from outside again");
        assertEquals(List.of(ALICE, ALICE), captured);
        assertEquals(List.of(alice), shown, "later lobby admission does not repeat the intro");
    }

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
        assertEquals(Optional.of("Leave your match or spectating first."), entry.join(alice));
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
