package com.ninja6.ninjaarena.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Lobby edit mode and reset, after command permission and argument checks. */
public interface LobbyCommands {

    /** Starts lobby edit mode; {@code label} is the root command as typed. */
    void edit(Player player, String label);

    void done(Player player);

    void reset(CommandSender sender);
}
