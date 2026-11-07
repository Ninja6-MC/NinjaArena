package com.ninja6.ninjaarena.command;

import com.ninja6.ninjaarena.texture.PlayerKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** The texture quality actions behind {@code /ninjaarena textures} and its admin reset. */
public interface TextureCommands {

    /** Opens the quality menu for a player who holds {@code ninjaarena.play}. */
    void open(Player player);

    /** Durably resets an identity to Standard and reports the outcome to {@code sender}. */
    void reset(CommandSender sender, PlayerKey key);
}
