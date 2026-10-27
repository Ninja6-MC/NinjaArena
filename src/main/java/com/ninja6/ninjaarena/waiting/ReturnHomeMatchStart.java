package com.ninja6.ninjaarena.waiting;

import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/**
 * The {@link MatchStart} used until the round loop exists: it logs the teams and sends every
 * player home, so nobody is left in the arena world with no one in charge of them.
 */
public final class ReturnHomeMatchStart implements MatchStart {

    private final Server server;
    private final Logger logger;
    private final Function<Player, RestoreResult> restore;

    /**
     * @param restore {@code RestoreService#restore}
     */
    public ReturnHomeMatchStart(Server server, Logger logger, Function<Player, RestoreResult> restore) {
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.restore = Objects.requireNonNull(restore, "restore");
    }

    @Override
    public void start(String arena, List<List<UUID>> teams) {
        logger.info("Countdown finished in arena " + arena + " with teams " + teams
                + "; rounds are not available yet, so the players are sent home.");
        for (List<UUID> team : teams) {
            for (UUID playerId : team) {
                Player player = server.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    player.sendMessage(Component.text("Rounds are not available on this server yet, so you were"
                            + " sent back.", NamedTextColor.YELLOW));
                    restore.apply(player);
                }
            }
        }
    }
}
