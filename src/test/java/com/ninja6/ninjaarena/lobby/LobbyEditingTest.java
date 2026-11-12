package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class LobbyEditingTest extends LobbyFixture {

    private final LobbyMembers members = members();
    private final LobbyBuildTest.RecordingSite site = new LobbyBuildTest.RecordingSite();
    private final List<String> calls = new ArrayList<>();
    private final LobbyEditing editing = new LobbyEditing(new LobbyBuild(layout, site, quiet()), this::spawn,
            id -> members.isMember(id) || busyElsewhere.contains(id), () -> calls.add("save"),
            () -> calls.add("players to spawn"), quiet());

    @Test
    void editTakesAnAdminFromElsewhereToTheSpawnAndDoneSavesAndReturnsThem() {
        Location home = new Location(overworld, 100, 70, -40);
        Player alice = add(ALICE, "Alice", home);

        editing.edit(alice, "narena");
        assertTrue(editing.isEditing(ALICE));
        assertEquals(spawn(), players.get(ALICE).location);
        assertEquals("Lobby edit mode is on: you can change the lobby. Use /narena admin lobby done to save and"
                + " finish.", players.get(ALICE).messages.getLast());

        editing.done(alice);
        assertFalse(editing.isEditing(ALICE));
        assertEquals(List.of("save"), calls);
        assertEquals(home, players.get(ALICE).location);
        assertEquals(List.of(true, true), players.get(ALICE).ownTeleports);
        assertEquals("Lobby edit mode is off and the lobby is saved.", players.get(ALICE).messages.getLast());
    }

    @Test
    void anAdminAlreadyInTheLobbyWorldStaysWhereTheyAre() {
        Location there = inLobby(3.5, 65, 3.5);
        Player alice = add(ALICE, "Alice", there);
        editing.edit(alice, "narena");
        editing.done(alice);
        assertEquals(there, players.get(ALICE).location);
        assertEquals(List.of(), players.get(ALICE).ownTeleports);
    }

    @Test
    void membersAndBusyPlayersCannotEditAndEditTwiceOrDoneWithoutEditSayWhy() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 0, 70, 0));
        members.enter(alice);
        editing.edit(alice, "narena");
        assertFalse(editing.isEditing(ALICE));
        assertEquals("Leave your match, waiting room or the lobby before editing the lobby.",
                players.get(ALICE).messages.getLast());

        Player bob = add(BOB, "Bob", new Location(overworld, 0, 70, 0));
        editing.done(bob);
        assertEquals("You are not editing the lobby.", players.get(BOB).messages.getLast());
        editing.edit(bob, "narena");
        editing.edit(bob, "narena");
        assertEquals("You are already editing the lobby. Use /narena admin lobby done to finish.",
                players.get(BOB).messages.getLast());
        assertEquals(List.of(), calls);
    }

    @Test
    void aRefusedTeleportDoesNotStartEditMode() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 0, 70, 0));
        players.get(ALICE).refuseTeleport = true;
        editing.edit(alice, "narena");
        assertFalse(editing.isEditing(ALICE));
        assertEquals("You could not be taken to the lobby.", players.get(ALICE).messages.getLast());
    }

    @Test
    void resetPutsTheBundledBuildBackSavesAndReturnsPlayersToTheSpawn() {
        List<String> told = new ArrayList<>();
        editing.reset(sender(told));
        assertEquals(List.of("clear " + layout.bounds().describe(), "place -20 64 -16", "mark"), site.calls);
        assertEquals(List.of("save", "players to spawn"), calls);
        assertEquals(List.of("The lobby is back to the bundled build. Changes made in edit mode are gone."), told);
    }

    @Test
    void aFailedResetSaysWhyAndChangesNothingElse() {
        site.failing = true;
        List<String> told = new ArrayList<>();
        editing.reset(sender(told));
        assertEquals(List.of(), calls);
        assertEquals(List.of("The lobby could not be reset: no structure"), told);
    }

    @Test
    void leavingEndsEditModeWithoutAReturnAndCloseSavesOpenEdits() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 0, 70, 0));
        Player bob = add(BOB, "Bob", new Location(overworld, 0, 70, 0));
        editing.edit(alice, "narena");
        editing.edit(bob, "narena");
        assertTrue(editing.stop(alice, false));
        assertFalse(editing.stop(alice, false));
        assertEquals(1, players.get(ALICE).ownTeleports.size(), "no return trip");

        editing.close();
        assertFalse(editing.isEditing(BOB));
        assertEquals(List.of("save"), calls);
    }

    private static CommandSender sender(List<String> told) {
        return (CommandSender) java.lang.reflect.Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "sendMessage" -> {
                        told.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
