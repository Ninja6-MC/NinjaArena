package com.ninja6.ninjaarena.lobby;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.resourcepack.TextureMenuMode;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/** First-visit help, remembered in the player's saved data. */
public final class LobbyIntro {

    private static final NamespacedKey SEEN = new NamespacedKey("ninjaarena", "intro_seen");
    private static final List<String> LINES = List.of("intro.tools", "intro.shop", "intro.hazards");
    private final Supplier<TextureMenuMode> mode;
    private final Consumer<Player> dialog;
    private final Predicate<Player> inLobby;

    public LobbyIntro(Supplier<TextureMenuMode> mode, Consumer<Player> dialog, Predicate<Player> inLobby) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.dialog = Objects.requireNonNull(dialog, "dialog");
        this.inLobby = Objects.requireNonNull(inLobby, "inLobby");
    }

    /** Called only after a successful lobby entry. */
    public void arrived(Player player) {
        if (!player.getPersistentDataContainer().has(SEEN, PersistentDataType.BYTE)) {
            show(player);
        }
    }

    /** Explicit replay, including for a player who has already seen the intro. */
    public void open(Player player) {
        if (!inLobby.test(player)) {
            player.sendMessage(msg("intro.lobby-only"));
            return;
        }
        show(player);
    }

    private void show(Player player) {
        if (mode.get() == TextureMenuMode.CHEST) {
            chat(player);
        } else {
            try {
                dialog.accept(player);
            } catch (RuntimeException | LinkageError e) {
                chat(player);
            }
        }
        player.getPersistentDataContainer().set(SEEN, PersistentDataType.BYTE, (byte) 1);
    }

    private static void chat(Player player) {
        player.sendMessage(msg("intro.title"));
        LINES.forEach(key -> player.sendMessage(msg(key)));
    }

    /** A notice needs no server callback: its only button closes it. */
    public static void showDialog(Player player) {
        List<DialogBody> body = LINES.stream()
                .map(key -> (DialogBody) DialogBody.plainMessage(msg(key))).toList();
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(msg("intro.title"))
                        .body(body)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.notice(ActionButton.builder(msg("intro.close")).width(200).build()))));
    }
}
