package com.ninja6.ninjaarena.command;

import org.bukkit.entity.Player;

/** Player-issued hosting operations, after command permission and argument checks. */
public interface HostingCommands {
    void host(Player player, String arena);

    void invite(Player player, String invitee);

    void accept(Player player, String host);
}
