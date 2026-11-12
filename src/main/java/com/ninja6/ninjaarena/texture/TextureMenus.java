package com.ninja6.ninjaarena.texture;

import com.ninja6.ninjaarena.message.Messages;
import com.ninja6.ninjaarena.resourcepack.TextureMenuMode;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.entity.Player;

/** Opens the configured texture screen, falling back to the chest menu if the dialog fails. */
public final class TextureMenus {

    private final TextureMenuMode mode;
    private final TextureMenu chest;
    private final TextureDialog dialog;
    private final Consumer<String> log;
    private final AtomicBoolean fellBack = new AtomicBoolean();

    public TextureMenus(TextureMenuMode mode, TextureMenu chest, TextureDialog dialog, Consumer<String> log) {
        this.mode = mode;
        this.chest = chest;
        this.dialog = dialog;
        this.log = log;
    }

    public void open(Player player) {
        if (mode == TextureMenuMode.CHEST) {
            chest.open(player);
            return;
        }
        try {
            dialog.open(player);
        } catch (RuntimeException | LinkageError e) {
            if (fellBack.compareAndSet(false, true)) {
                log.accept("The texture dialog could not be shown (" + e.getClass().getSimpleName() + ": "
                        + e.getMessage() + "). Using the chest menu instead; set resource-pack.texture-menu to"
                        + " chest to skip the dialog.");
            }
            chest.open(player);
        }
    }

    /** The screen in use, for {@code /narena pack status}. */
    public String describe() {
        return mode == TextureMenuMode.DIALOG && fellBack.get()
                ? Messages.text("pack.menu-fell-back", "mode", mode.label()) : mode.label();
    }

    public void forget(UUID player) {
        chest.forget(player);
        dialog.forget(player);
    }

    public void closeAll(Function<UUID, Player> players) {
        chest.closeAll(players);
        dialog.closeAll(players);
    }
}
