package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.restore.OwnTeleports;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.Test;

class LobbyListenerTest extends LobbyFixture {

    private final LobbyMembers members = members();
    private final List<String> saves = new ArrayList<>();
    private final LobbyEditing editing = new LobbyEditing(
            new LobbyBuild(layout, new LobbyBuildTest.RecordingSite(), quiet()), this::spawn,
            id -> members.isMember(id), () -> saves.add("save"), () -> { }, quiet());
    private final LobbyListener listener = new LobbyListener(members, editing);

    @SuppressWarnings("removal")
    private static EntityDamageEvent damage(Entity entity, DamageCause cause) {
        return new EntityDamageEvent(entity, cause, source(), 4);
    }

    @SuppressWarnings("removal")
    private static EntityDamageByEntityEvent hit(Entity damager, Entity entity) {
        return new EntityDamageByEntityEvent(damager, entity, DamageCause.ENTITY_ATTACK, source(), 4);
    }

    private static DamageSource source() {
        return (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                new Class<?>[] {DamageSource.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Entity stand(World world) {
        return (Entity) Proxy.newProxyInstance(ArmorStand.class.getClassLoader(), new Class<?>[] {ArmorStand.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Block block(World world) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void noDamageIsTakenInTheLobbyWorldByAnyoneOrAnything() {
        Player alice = add(ALICE, "Alice", inLobby(0.5, 65, 0.5));
        for (DamageCause cause : List.of(DamageCause.FALL, DamageCause.ENTITY_ATTACK, DamageCause.KILL,
                DamageCause.LAVA)) {
            EntityDamageEvent event = damage(alice, cause);
            listener.onDamage(event);
            assertTrue(event.isCancelled(), cause.name());
        }
        EntityDamageEvent standHit = hit(alice, stand(lobbyWorld));
        listener.onDamage(standHit);
        assertTrue(standHit.isCancelled());

        Player bob = add(BOB, "Bob", new Location(overworld, 0, 70, 0));
        EntityDamageEvent elsewhere = damage(bob, DamageCause.FALL);
        listener.onDamage(elsewhere);
        assertFalse(elsewhere.isCancelled());
    }

    @Test
    void aVoidFallReturnsAnyoneInTheLobbyWorldToTheSpawnInsteadOfKillingThem() {
        Player alice = add(ALICE, "Alice", inLobby(0.5, -130, 0.5));
        EntityDamageEvent fall = damage(alice, DamageCause.VOID);
        listener.onDamage(fall);
        assertTrue(fall.isCancelled());
        assertEquals(spawn(), players.get(ALICE).location);
        assertEquals(0, players.get(ALICE).fallDistance);
        assertEquals(List.of(true), players.get(ALICE).ownTeleports, "own, so a match keeps its player");
    }

    @Test
    void aMemberWhoLeavesTheBoundsIsReturnedButOneInsideIsNot() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 0, 70, 0));
        members.enter(alice);
        Location from = spawn();

        listener.onMove(new PlayerMoveEvent(alice, from, inLobby(0.5, 65, 5.5)));
        assertTrue(nextTick.isEmpty());

        // Off the platform and below the bounds.
        players.get(ALICE).location = inLobby(30.5, 50, 0.5);
        listener.onMove(new PlayerMoveEvent(alice, inLobby(30.5, 57, 0.5), inLobby(30.5, 50, 0.5)));
        runNextTick();
        assertEquals(spawn(), players.get(ALICE).location);

        // A visitor who is not a member is not held to the bounds.
        Player bob = add(BOB, "Bob", inLobby(30.5, 50, 0.5));
        listener.onMove(new PlayerMoveEvent(bob, inLobby(30.5, 57, 0.5), inLobby(30.5, 50, 0.5)));
        assertTrue(nextTick.isEmpty());
    }

    @Test
    void aMemberTeleportedOutOfTheLobbyWorldLeavesAndIsSentHomeUnlessItWasNinjaArena() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 0, 70, 0));
        Player bob = add(BOB, "Bob", new Location(overworld, 0, 70, 0));
        members.enter(alice);
        members.enter(bob);

        listener.onTeleport(new PlayerTeleportEvent(alice, spawn(), new Location(overworld, 9, 70, 9),
                TeleportCause.COMMAND));
        assertFalse(members.isMember(ALICE));
        runNextTick();
        assertEquals(List.of(ALICE), sentHome);

        OwnTeleports.during(BOB, () -> {
            listener.onTeleport(new PlayerTeleportEvent(bob, spawn(), new Location(arenaWorld, 0, 64, 0),
                    TeleportCause.PLUGIN));
            return true;
        });
        assertFalse(members.isMember(BOB));
        runNextTick();
        assertEquals(List.of(ALICE), sentHome, "a room moving its player places them itself");
    }

    @Test
    void aQuitEndsMembershipWithoutSendingHome() {
        Player alice = add(ALICE, "Alice", new Location(overworld, 0, 70, 0));
        members.enter(alice);
        listener.onQuit(new PlayerQuitEvent(alice, net.kyori.adventure.text.Component.empty()));
        assertFalse(members.isMember(ALICE));
        runNextTick();
        assertEquals(List.of(), sentHome);
    }

    @Test
    void onlyAnEditorMayChangeTheLobby() {
        Player alice = add(ALICE, "Alice", inLobby(0.5, 65, 0.5));
        BlockBreakEvent visitor = new BlockBreakEvent(block(lobbyWorld), alice);
        listener.onBreak(visitor);
        assertTrue(visitor.isCancelled());

        editing.edit(alice, "narena");
        BlockBreakEvent editor = new BlockBreakEvent(block(lobbyWorld), alice);
        listener.onBreak(editor);
        assertFalse(editor.isCancelled());
        EntityDamageEvent standHit = hit(alice, stand(lobbyWorld));
        listener.onDamage(standHit);
        assertFalse(standHit.isCancelled(), "an editor may remove an armour stand");

        Player bob = add(BOB, "Bob", inLobby(0.5, 65, 0.5));
        BlockBreakEvent other = new BlockBreakEvent(block(lobbyWorld), bob);
        listener.onBreak(other);
        assertTrue(other.isCancelled(), "protection is lifted for the editor alone");

        BlockBreakEvent outside = new BlockBreakEvent(block(overworld), bob);
        listener.onBreak(outside);
        assertFalse(outside.isCancelled());
    }

    @Test
    void anEditorWhoLeavesTheLobbyWorldStopsEditing() {
        Player alice = add(ALICE, "Alice", inLobby(0.5, 65, 0.5));
        editing.edit(alice, "narena");
        listener.onTeleport(new PlayerTeleportEvent(alice, spawn(), inLobby(5, 65, 5), TeleportCause.COMMAND));
        assertTrue(editing.isEditing(ALICE));

        listener.onTeleport(new PlayerTeleportEvent(alice, spawn(), new Location(overworld, 0, 70, 0),
                TeleportCause.COMMAND));
        assertFalse(editing.isEditing(ALICE));
        assertEquals("Lobby edit mode ended because you left the lobby world.",
                players.get(ALICE).messages.getLast());
    }
}
