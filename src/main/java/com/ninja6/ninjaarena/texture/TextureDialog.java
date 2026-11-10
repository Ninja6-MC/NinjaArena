package com.ninja6.ninjaarena.texture;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

/**
 * The "Arena Texture Quality" dialog screen. It uses compact text and runs the same
 * {@link TextureService} calls as the chest menu. A dialog has no close event, so the player's
 * current dialog is the latest one opened; a press from any other is stale. Every press rechecks
 * ownership and permission.
 */
public final class TextureDialog {

    public enum Button { STANDARD, DETAILED, CLOSE }

    /** The text of one dialog: the body lines and the two choice labels. */
    public record Content(String title, List<String> lines, String standard, String detailed) {
    }

    /** A press, as the platform reports it. {@code presser} is the player who pressed the button. */
    @FunctionalInterface
    public interface Press {
        void pressed(Player presser, Button button);
    }

    /** Sends dialogs; the server-backed implementation lives below. May throw if the dialog cannot be sent. */
    public interface Screens {
        void show(Player player, Content content, Press press);

        void close(Player player);
    }

    private static final class Active {
        private final long token;
        private Quality saving;
        private String note = "";

        private Active(long token) {
            this.token = token;
        }
    }

    private final TextureService service;
    private final Screens screens;
    private final TextureService.Notifier notifier;
    private final Map<UUID, Active> open = new HashMap<>();
    private final Predicate<UUID> locked;
    private long tokens;

    public TextureDialog(TextureService service, Screens screens, TextureService.Notifier notifier) {
        this(service, screens, notifier, id -> false);
    }

    /** {@code locked} is the inventory lockdown condition: in a match, waiting for one, or spectating. */
    public TextureDialog(TextureService service, Screens screens, TextureService.Notifier notifier,
            Predicate<UUID> locked) {
        this.locked = Objects.requireNonNull(locked, "locked");
        this.service = Objects.requireNonNull(service, "service");
        this.screens = Objects.requireNonNull(screens, "screens");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    /** Opens the dialog. Throws, leaving nothing open, if it cannot be built or sent. */
    public void open(Player player) {
        UUID id = player.getUniqueId();
        if (!player.hasPermission(TextureMenu.PERMISSION)) {
            notifier.tell(id, "You do not have permission to use this command.");
            return;
        }
        if (locked.test(id)) {
            notifier.tell(id, TextureMenu.LOCKED);
            return;
        }
        Active active = new Active(++tokens);
        open.put(id, active);
        try {
            render(player, active);
        } catch (RuntimeException | LinkageError e) {
            open.remove(id, active);
            throw e;
        }
    }

    private void press(Player presser, UUID owner, long token, Button button) {
        UUID id = presser.getUniqueId();
        Active active = open.get(owner);
        if (!owner.equals(id) || active == null || active.token != token) {
            screens.close(presser);
            notifier.tell(id, "That menu is out of date. Reopen it with /ninjaarena textures.");
            return;
        }
        if (!presser.hasPermission(TextureMenu.PERMISSION)) {
            open.remove(id);
            screens.close(presser);
            notifier.tell(id, "You do not have permission to use this menu.");
            return;
        }
        if (locked.test(id)) {
            open.remove(id, active);
            screens.close(presser);
            notifier.tell(id, TextureMenu.LOCKED);
            return;
        }
        switch (button) {
            case STANDARD -> choose(presser, active, Quality.STANDARD);
            case DETAILED -> choose(presser, active, Quality.DETAILED);
            case CLOSE -> {
                open.remove(id, active);
                screens.close(presser);
            }
        }
    }

    private void choose(Player player, Active active, Quality quality) {
        UUID id = player.getUniqueId();
        if (active.saving != null) {
            notifier.tell(id, "Still saving your last choice. Wait for it to finish.");
            return;
        }
        active.saving = quality;
        show(player, active);
        service.choose(id, quality, result -> {
            if (open.get(id) == active) {
                active.saving = null;
                active.note = result.message();
                show(player, active);
            }
            notifier.tell(id, result.message());
        });
    }

    /** Re-shows after a press; a failure drops the dialog and points the player at the command. */
    private void show(Player player, Active active) {
        try {
            render(player, active);
        } catch (RuntimeException | LinkageError e) {
            open.remove(player.getUniqueId(), active);
            screens.close(player);
            notifier.tell(player.getUniqueId(), "The texture menu could not be updated. Reopen it with"
                    + " /ninjaarena textures.");
        }
    }

    private void render(Player player, Active active) {
        UUID owner = player.getUniqueId();
        long token = active.token;
        screens.show(player, content(service.describe(owner), active.saving, active.note),
                (presser, button) -> press(presser, owner, token, button));
    }

    public void forget(UUID player) {
        open.remove(player);
    }

    public void closeAll(Function<UUID, Player> players) {
        for (UUID id : List.copyOf(open.keySet())) {
            Player player = players.apply(id);
            open.remove(id);
            if (player != null) {
                screens.close(player);
            }
        }
    }

    /** Three short lines leave room for the choice buttons on a Bedrock form. */
    static Content content(TextureService.View view, Quality saving, String note) {
        String choice = TextureMenu.choiceLine(view);
        String status = saving != null ? "Saving..." : view.playerStatus();
        if (saving == null && note.startsWith("Could not save")) {
            status = "Could not save. Try again later.";
        } else if (saving == null && !view.storage().isEmpty()) {
            status = "Storage unavailable. Try again later.";
        } else if (saving == null && view.bedrock() && view.key().isEmpty()) {
            status = "Account unverified; choices unavailable.";
        }
        String timing = view.bedrock() ? "Changes apply next connection."
                : view.sessionOnly() ? "For this connection; resets on leave."
                : "Applies now, or reconnect if needed.";
        return new Content(TextureMenu.TITLE, List.of(choice, status, timing), "Standard 16x16",
                view.detailedAvailable() ? "Detailed 32x32" : "Detailed 32x32 (unavailable)");
    }

    /** Builds and sends Paper dialogs. Button callbacks hop to the server thread before running. */
    public static final class PaperScreens implements Screens {
        private final Executor mainThread;

        public PaperScreens(Executor mainThread) {
            this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        }

        @Override
        public void show(Player player, Content content, Press press) {
            ClickCallback.Options options = ClickCallback.Options.builder()
                    .uses(ClickCallback.UNLIMITED_USES).build();
            List<ActionButton> buttons = List.of(
                    button(content.standard(), Button.STANDARD, press, options),
                    button(content.detailed(), Button.DETAILED, press, options),
                    button("Close", Button.CLOSE, press, options));
            List<DialogBody> body = content.lines().stream()
                    .map(line -> (DialogBody) DialogBody.plainMessage(Component.text(line))).toList();
            Dialog dialog = Dialog.create(factory -> factory.empty()
                    .base(DialogBase.builder(Component.text(content.title()))
                            .body(body)
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.NONE)
                            .build())
                    .type(DialogType.multiAction(buttons).columns(1).build()));
            player.showDialog(dialog);
        }

        private ActionButton button(String label, Button button, Press press, ClickCallback.Options options) {
            return ActionButton.builder(Component.text(label))
                    .width(200)
                    .action(DialogAction.customClick((response, audience) -> relay(audience, button, press), options))
                    .build();
        }

        private void relay(Audience audience, Button button, Press press) {
            if (audience instanceof Player presser) {
                // The callback is not guaranteed to run on the server thread.
                mainThread.execute(() -> press.pressed(presser, button));
            }
        }

        @Override
        public void close(Player player) {
            player.closeDialog();
        }
    }
}
