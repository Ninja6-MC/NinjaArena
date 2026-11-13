package com.ninja6.ninjaarena.command;

import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** The {@code room} subcommands, after the command's permission and argument checks. */
public interface RoomCommands {

    /**
     * Shows the player's room, with kick buttons for its host; outside a room, the room list
     * with join buttons and the featured map of a running event.
     */
    void show(Player player);

    void create(Player player);

    /** Shows the settings when {@code setting} is {@code null}, otherwise changes one. */
    void settings(Player player, String setting, String value);

    /** Lists lobby players to invite, with buttons, when {@code invitee} is {@code null}. */
    void invite(Player player, String invitee);

    void accept(Player player, String host);

    void kick(Player player, String member);

    /**
     * Starts the match of the sender's room, or of the room {@code host} runs when given,
     * which only an admin may do for someone else's room.
     */
    void start(CommandSender sender, String host);

    void mapSet(Player player, String arena);

    /**
     * Plays generated maps: from the seed modes for a {@code null} seed, otherwise from the
     * seed set by hand, at the difficulty or {@code maps.default.difficulty} for {@code null}.
     */
    void mapSetGenerated(Player player, Long seed, Integer difficulty);

    /** The next rotation seed at the next generation of the room's map. */
    void mapNext(Player player);

    /** A new random seed at the next generation of the room's map. */
    void mapReroll(Player player);

    void mapList(Player player);

    /** Arena names offered for room map selection. */
    List<String> pickableMaps();

    /** Leaves the room and stays in the lobby. */
    void leave(Player player);
}
