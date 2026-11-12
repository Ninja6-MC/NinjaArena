package com.ninja6.ninjaarena.lobby;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.command.LobbyCommands;
import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Lobby edit mode: {@code /ninjaarena admin lobby edit}, {@code done} and {@code reset}.
 *
 * <p>An admin in edit mode may change the lobby: the lobby protection is lifted for them
 * alone, while every other player, members included, stays protected. An admin who was
 * elsewhere is taken to the lobby spawn and returned there by {@code done}. {@code done}
 * also saves the lobby world. Leaving the lobby world or the server ends edit mode without
 * a return. Edit mode is not lobby membership: the editor's inventory is not locked.
 */
public final class LobbyEditing implements LobbyCommands {

    private final LobbyBuild build;
    private final Supplier<Location> spawn;
    private final Predicate<UUID> busy;
    private final Runnable save;
    private final Runnable afterReset;
    private final Logger logger;
    /** Editors, each with the place {@code done} returns them to, if they came from outside. */
    private final Map<UUID, Optional<Location>> editors = new HashMap<>();

    /**
     * @param spawn      the lobby spawn
     * @param busy       whether a player is a lobby member, in a match, a waiting room or the
     *                   hub, or spectating; such a player cannot edit
     * @param save       saves the lobby world
     * @param afterReset runs once the bundled build is back, to put players at the spawn
     */
    public LobbyEditing(LobbyBuild build, Supplier<Location> spawn, Predicate<UUID> busy, Runnable save,
            Runnable afterReset, Logger logger) {
        this.build = Objects.requireNonNull(build, "build");
        this.spawn = Objects.requireNonNull(spawn, "spawn");
        this.busy = Objects.requireNonNull(busy, "busy");
        this.save = Objects.requireNonNull(save, "save");
        this.afterReset = Objects.requireNonNull(afterReset, "afterReset");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean isEditing(UUID id) {
        return editors.containsKey(id);
    }

    /** Starts edit mode, taking the admin to the lobby spawn if they are not in the lobby world. */
    @Override
    public void edit(Player player, String label) {
        UUID id = player.getUniqueId();
        if (editors.containsKey(id)) {
            player.sendMessage(msg("lobby.edit-already", "label", label));
            return;
        }
        if (busy.test(id)) {
            player.sendMessage(msg("lobby.edit-busy"));
            return;
        }
        Optional<Location> back = Optional.empty();
        if (!PluginWorlds.isLobby(player.getLocation())) {
            Location from = player.getLocation().clone();
            if (!OwnTeleports.teleport(player, spawn.get(), TeleportCause.PLUGIN)) {
                player.sendMessage(msg("lobby.edit-failed"));
                return;
            }
            back = Optional.of(from);
        }
        editors.put(id, back);
        logger.info(player.getName() + " started editing the lobby.");
        player.sendMessage(msg("lobby.edit-started", "label", label));
    }

    /** Ends edit mode, saves the lobby world and returns the admin to where they came from. */
    @Override
    public void done(Player player) {
        Optional<Location> back = editors.remove(player.getUniqueId());
        if (back == null) {
            player.sendMessage(msg("lobby.not-editing"));
            return;
        }
        save.run();
        logger.info(player.getName() + " finished editing the lobby; the lobby world was saved.");
        player.sendMessage(msg("lobby.edit-done"));
        back.ifPresent(location -> OwnTeleports.teleport(player, location, TeleportCause.PLUGIN));
    }

    /** Puts the bundled build back. Editors stay in edit mode. */
    @Override
    public void reset(CommandSender sender) {
        try {
            build.reset();
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "Could not reset the lobby build.", e);
            sender.sendMessage(msg("lobby.reset-failed", "reason", String.valueOf(e.getMessage())));
            return;
        }
        save.run();
        afterReset.run();
        sender.sendMessage(msg("lobby.reset"));
    }

    /**
     * Ends edit mode for an admin who left the lobby world or the server; nothing is returned
     * or saved beyond what the world saves on its own.
     *
     * @return whether they were editing
     */
    public boolean stop(Player player, boolean tell) {
        if (editors.remove(player.getUniqueId()) == null) {
            return false;
        }
        logger.info(player.getName() + " stopped editing the lobby by leaving it.");
        if (tell) {
            player.sendMessage(msg("lobby.edit-ended"));
        }
        return true;
    }

    /** Ends every edit and saves the lobby world. Called on plugin disable. */
    public void close() {
        if (!editors.isEmpty()) {
            editors.clear();
            save.run();
        }
    }
}
