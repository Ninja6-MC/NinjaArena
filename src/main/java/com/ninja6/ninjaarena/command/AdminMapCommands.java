package com.ninja6.ninjaarena.command;

import org.bukkit.command.CommandSender;

/** The {@code admin generate} subcommands, after the command's permission and argument checks. */
public interface AdminMapCommands {

    /**
     * Generates the layout of a seed in a free slot cell for an admin to look at.
     *
     * @param difficulty null for {@code maps.default.difficulty}
     * @param teams      null for 2
     */
    void generate(CommandSender sender, long seed, Integer difficulty, Integer teams);

    /** Clears the admin's generated layout and frees its cell. */
    void free(CommandSender sender);
}
