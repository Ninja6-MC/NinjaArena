package com.ninja6.ninjaarena.room;

import com.ninja6.ninjaarena.dialog.DialogRelay;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

/** Native Paper dialogs. All callbacks return to the server thread, including exit actions. */
public final class RoomDialog implements RoomScreen.Screens {
    // Room screens redraw on actions and can be reopened after thirty minutes idle.
    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(30);
    static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES).lifetime(CALLBACK_LIFETIME).build();
    private final Executor main;
    private final Function<UUID, Player> players;

    public RoomDialog(Executor main, Function<UUID, Player> players) {
        this.main = main;
        this.players = players;
    }

    @Override
    public void show(Player player, RoomScreen screen, RoomScreen.Press press) {
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < screen.options().size(); i++) {
            buttons.add(button(screen.options().get(i).label(), i, screen, press));
        }
        List<DialogInput> inputs = new ArrayList<>();
        if (screen.input() != null) {
            var input = screen.input();
            List<OptionEntry> entries = new ArrayList<>();
            for (int i = 0; i < input.values().size(); i++) {
                String value = input.values().get(i);
                entries.add(OptionEntry.create(value, Component.text(input.labels().get(i)),
                        value.equals(input.initial())));
            }
            inputs.add(DialogInput.singleOption("value", Component.text(input.label()), entries).build());
        }
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(screen.title()))
                        .body(screen.lines().stream().map(line -> (DialogBody) DialogBody.plainMessage(
                                Component.text(line))).toList())
                        .inputs(inputs).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE).build())
                .type(DialogType.multiAction(buttons).columns(1)
                        .exitAction(button(screen.exit(), -1, screen, press)).build())));
    }

    private ActionButton button(String label, int index, RoomScreen screen, RoomScreen.Press press) {
        return ActionButton.builder(Component.text(label)).width(200)
                .action(DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player) {
                        String value = screen.input() == null ? null : response.getText("value");
                        Map<String, String> values = value == null ? Map.of() : Map.of("value", value);
                        DialogRelay.onMain(audience, main, players,
                                current -> press.run(current, index, values));
                    }
                }, CALLBACK_OPTIONS)).build();
    }

    @Override
    public void close(Player player) {
        player.closeDialog();
    }
}
