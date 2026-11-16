package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.kit.InventoryLockdownListener;
import com.ninja6.ninjaarena.restore.ArenaBusy;
import java.lang.reflect.Proxy;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.junit.jupiter.api.Test;

class LobbyMembersTest extends LobbyFixture {
    private static final String LEFT_LOBBY = "You left the lobby. /narena leave has nothing more to leave.";
    private static final String RESTORE_FAILED = "Your belongings could not be returned yet. Rejoin the server,"
            + " or ask an admin if this keeps happening.";


    private final LobbyMembers members = members();

    @Test
    void enteringTakesThePlayerToTheSpawnEmptiesThemAndMakesThemAMember() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));

        assertTrue(members.enter(alice));

        assertTrue(members.isMember(ALICE));
        assertEquals(spawn(), players.get(ALICE).location);
        assertEquals(List.of(true), players.get(ALICE).ownTeleports);
        assertEquals(List.of(ALICE), emptied);
        assertEquals(List.of("You are in the lobby."), players.get(ALICE).messages);
        assertEquals(List.of(ALICE), members.members());
    }

    @Test
    void aPlayerBusyElsewhereOrAlreadyInCannotEnter() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        busyElsewhere.add(ALICE);
        assertFalse(members.enter(alice));
        assertFalse(members.isMember(ALICE));
        assertEquals(List.of(), players.get(ALICE).ownTeleports);

        busyElsewhere.clear();
        assertTrue(members.enter(alice));
        assertFalse(members.enter(alice));
        assertEquals(List.of(ALICE), emptied);
    }

    @Test
    void aRefusedTeleportLeavesThePlayerAsTheyWere() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        players.get(ALICE).refuseTeleport = true;
        assertFalse(members.enter(alice));
        assertFalse(members.isMember(ALICE));
        assertEquals(List.of(), emptied);
    }

    @Test
    void thePlayerWhoCannotBeEmptiedIsSentHome() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        LobbyMembers failing = new LobbyMembers(plugin, layout, () -> lobbyWorld, id -> false, player -> {
            throw new IllegalStateException("inventory");
        }, player -> {
            sentHome.add(player.getUniqueId());
            return com.ninja6.ninjaarena.restore.RestoreService.RestoreResult.RESTORED;
        });
        assertFalse(failing.enter(alice));
        assertFalse(failing.isMember(ALICE));
        assertEquals(List.of(ALICE), sentHome);
    }

    @Test
    void leavingByCommandSendsHomeAtOnce() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        members.enter(alice);

        assertTrue(members.leave(alice, LobbyMembers.Exit.COMMAND));
        assertEquals(LEFT_LOBBY,
                players.get(ALICE).messages.getLast());
        assertFalse(members.isMember(ALICE));
        assertEquals(List.of(ALICE), sentHome);
        assertFalse(members.leave(alice, LobbyMembers.Exit.COMMAND));
    }

    @Test
    void aFailedRestoreHomeIsReportedAfterTheLeaveMessageWhichNeverSaysHome() {
        LobbyMembers failing = new LobbyMembers(plugin, layout, () -> lobbyWorld, id -> false, player -> { },
                player -> com.ninja6.ninjaarena.restore.RestoreService.RestoreResult.FAILED);
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        Player bob = add(BOB, "Bob", new Location(overworld, 5, 70, 5));
        failing.enter(alice);
        failing.enter(bob);

        assertTrue(failing.leave(alice, LobbyMembers.Exit.COMMAND));
        assertEquals(List.of("You are in the lobby.", LEFT_LOBBY, RESTORE_FAILED), players.get(ALICE).messages);

        assertTrue(failing.leave(bob, LobbyMembers.Exit.TELEPORTED));
        assertEquals(List.of("You are in the lobby.", LEFT_LOBBY), players.get(BOB).messages);
        runNextTick();
        assertEquals(List.of("You are in the lobby.", LEFT_LOBBY, RESTORE_FAILED), players.get(BOB).messages);
    }

    @Test
    void aTeleportOutSendsHomeOnTheNextTickAndAMoveOrQuitSendsNobodyHome() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        Player bob = add(BOB, "Bob", new Location(overworld, 5, 70, 5));
        members.enter(alice);
        members.enter(bob);

        members.leave(alice, LobbyMembers.Exit.TELEPORTED);
        assertEquals(LEFT_LOBBY,
                players.get(ALICE).messages.getLast());
        assertEquals(List.of(), sentHome, "not inside the teleport event");
        runNextTick();
        assertEquals(List.of(ALICE), sentHome);

        members.leave(bob, LobbyMembers.Exit.MOVED);
        Player again = players.get(BOB).proxy;
        members.enter(again);
        members.leave(again, LobbyMembers.Exit.QUIT);
        runNextTick();
        assertEquals(List.of(ALICE), sentHome);
    }

    @Test
    void closingSendsEveryMemberHome() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        members.enter(alice);
        members.close();
        assertFalse(members.isMember(ALICE));
        assertEquals(List.of(ALICE), sentHome);
    }

    @Test
    void walkingOutOfTheBoundsReturnsToTheSpawnOnceOnTheNextTick() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        members.enter(alice);
        players.get(ALICE).location = inLobby(0, 40, 0);

        members.returnToSpawnLater(ALICE);
        members.returnToSpawnLater(ALICE);
        assertEquals(1, nextTick.size());
        runNextTick();

        assertEquals(spawn(), players.get(ALICE).location);
        assertEquals(0, players.get(ALICE).fallDistance);
        assertTrue(players.get(ALICE).ownTeleports.getLast());
    }

    /** Membership, not the world, locks the inventory. */
    @Test
    void theInventoryLockdownGoesByMembershipNotByWorld() {
        ArenaBusy busy = new ArenaBusy(() -> null, () -> null, () -> members);
        InventoryLockdownListener lockdown = new InventoryLockdownListener(busy);
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        Player bob = add(BOB, "Bob", inLobby(0.5, 65, 0.5));
        members.enter(alice);

        PlayerDropItemEvent memberDrop = new PlayerDropItemEvent(alice, item());
        lockdown.onDrop(memberDrop);
        assertTrue(memberDrop.isCancelled());

        // Bob stands in the lobby world without being a member.
        PlayerDropItemEvent visitorDrop = new PlayerDropItemEvent(bob, item());
        lockdown.onDrop(visitorDrop);
        assertFalse(visitorDrop.isCancelled());

        members.leave(alice, LobbyMembers.Exit.MOVED);
        players.get(ALICE).location = inLobby(0.5, 65, 0.5);
        PlayerDropItemEvent formerDrop = new PlayerDropItemEvent(alice, item());
        lockdown.onDrop(formerDrop);
        assertFalse(formerDrop.isCancelled());
    }

    @Test
    void theJoinRestoreTreatsMembersAsBusy() {
        ArenaBusy busy = new ArenaBusy(() -> null, () -> null, () -> members);
        Player alice = add(ALICE, "Alice", new Location(overworld, 5, 70, 5));
        assertFalse(busy.test(ALICE));
        members.enter(alice);
        assertTrue(busy.test(ALICE));
        assertFalse(new ArenaBusy(() -> null, () -> null).test(ALICE));
    }

    private static Item item() {
        return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[] {Item.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
