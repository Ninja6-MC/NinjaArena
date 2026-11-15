package com.ninja6.ninjaarena.shop;

import static com.ninja6.ninjaarena.message.Messages.text;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.List;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

/** Text-only Paper dialog, also legible as a Geyser form without item pictures. */
public final class ShopDialog implements ShopMenus.Surface {
    private final Executor mainThread;

    public ShopDialog(Executor mainThread) { this.mainThread = mainThread; }

    @Override
    public void show(Player player, ShopMenus.Content content, ShopMenus.Press press) {
        List<ActionButton> buttons = content.buttons().stream()
                .map(button -> button(button.label(), button.action(), press)).toList();
        List<DialogBody> body = content.lines().stream()
                .map(line -> (DialogBody) DialogBody.plainMessage(Component.text(line))).toList();
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(content.title())).body(body)
                        .canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE).build())
                .type(DialogType.multiAction(buttons).columns(1)
                        .exitAction(button(text("shop.menu.close"), new ShopMenus.Action(ShopMenus.Kind.CLOSE), press))
                        .build())));
    }

    private ActionButton button(String label, ShopMenus.Action action, ShopMenus.Press press) {
        return ActionButton.builder(Component.text(label)).width(250)
                .action(DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player player) {
                        mainThread.execute(() -> press.pressed(player, action));
                    }
                }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build())).build();
    }

    @Override
    public void close(Player player) { player.closeDialog(); }
}
