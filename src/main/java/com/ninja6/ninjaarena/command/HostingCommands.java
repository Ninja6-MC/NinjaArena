package com.ninja6.ninjaarena.command;

import org.bukkit.entity.Player;

/**
 * {@code /narena host}, {@code invite} and {@code accept}, after command permission and
 * argument checks. They work on rooms: host opens an invite-only room on an arena, and
 * invite and accept are those of {@code /narena room}.
 */
public interface HostingCommands {
    void host(Player player, String arena);

    void invite(Player player, String invitee);

    void accept(Player player, String host);
}
