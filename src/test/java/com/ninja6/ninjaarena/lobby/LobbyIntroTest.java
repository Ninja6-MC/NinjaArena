package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.message.Messages;
import com.ninja6.ninjaarena.resourcepack.TextureMenuMode;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class LobbyIntroTest extends LobbyFixture {
    private final LobbyMembers members = members();
    private final Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
    private final List<Player> shown = new ArrayList<>();
    private TextureMenuMode mode = TextureMenuMode.DIALOG;
    private final LobbyIntro intro = service();

    private LobbyIntro service() {
        return new LobbyIntro(() -> mode, shown::add, player -> members.isMember(player.getUniqueId()));
    }

    private boolean seen() {
        return alice.getPersistentDataContainer().has(new NamespacedKey("ninjaarena", "intro_seen"),
                PersistentDataType.BYTE);
    }

    @Test
    void firstEntryShowsAndMarksButReentryAndAFreshServiceDoNot() {
        members.setArrival(intro::arrived);
        assertTrue(members.enter(alice));
        assertTrue(seen());
        assertEquals(List.of(alice), shown);
        assertFalse(members.enter(alice));
        members.leave(alice, LobbyMembers.Exit.MOVED);
        assertTrue(members.enter(alice));
        members.leave(alice, LobbyMembers.Exit.MOVED);
        members.setArrival(service()::arrived);
        assertTrue(members.enter(alice));
        assertEquals(List.of(alice), shown);
    }

    @Test
    void refusedEntryNeverShowsOrMarks() {
        members.setArrival(intro::arrived);
        busyElsewhere.add(ALICE);
        assertFalse(members.enter(alice));
        busyElsewhere.clear();
        players.get(ALICE).refuseTeleport = true;
        assertFalse(members.enter(alice));
        assertTrue(shown.isEmpty());
        assertFalse(seen());
    }

    @Test
    void failedInventoryPreparationNeverShowsOrMarks() {
        LobbyMembers failing = new LobbyMembers(plugin, layout, () -> lobbyWorld, id -> false,
                player -> { throw new IllegalStateException("inventory"); },
                player -> com.ninja6.ninjaarena.restore.RestoreService.RestoreResult.RESTORED);
        failing.setArrival(intro::arrived);
        assertFalse(failing.enter(alice));
        assertTrue(shown.isEmpty());
        assertFalse(seen());
    }

    @Test
    void replayRequiresMembershipAndWorksWhetherMarkedOrNot() {
        intro.open(alice);
        assertEquals(Messages.text("intro.lobby-only"), players.get(ALICE).messages.getLast());
        assertFalse(seen());
        members.enter(alice);
        intro.open(alice);
        intro.open(alice);
        assertEquals(List.of(alice, alice), shown);
        assertTrue(seen());
        members.leave(alice, LobbyMembers.Exit.MOVED);
        intro.open(alice);
        assertEquals(2, shown.size());
    }

    @Test
    void chestModeUsesTheSameBodyInChatAndMarksSeen() {
        mode = TextureMenuMode.CHEST;
        members.setArrival(intro::arrived);
        members.enter(alice);
        assertTrue(shown.isEmpty());
        assertTrue(seen());
        assertEquals(List.of(Messages.text("lobby.joined"), Messages.text("intro.title"),
                Messages.text("intro.tools"), Messages.text("intro.shop"), Messages.text("intro.hazards")),
                players.get(ALICE).messages);
    }

    @Test
    void unavailableDialogFallsBackToChatAndMarksSeen() {
        LobbyIntro failing = new LobbyIntro(() -> mode, player -> { throw new LinkageError("dialog"); },
                player -> true);
        failing.open(alice);
        assertTrue(seen());
        assertEquals(4, players.get(ALICE).messages.size());
        assertEquals(Messages.text("intro.hazards"), players.get(ALICE).messages.getLast());
    }
}
