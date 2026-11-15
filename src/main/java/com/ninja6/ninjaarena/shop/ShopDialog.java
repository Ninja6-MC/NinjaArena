package com.ninja6.ninjaarena.shop;

import static com.ninja6.ninjaarena.message.Messages.text;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

/** Text-only Paper dialog, also legible as a Geyser form without item pictures. */
public final class ShopDialog implements ShopMenus.Surface {
    private final Executor mainThread;
    private final Function<UUID, Player> players;

    public ShopDialog(Executor mainThread, Function<UUID, Player> players) {
        this.mainThread = mainThread;
        this.players = players;
    }

    @Override
    public void show(Player player, ShopMenus.Content content, ShopMenus.Press press) {
        List<ActionButton> buttons = content.buttons().stream()
                .map(button -> button(button.label(), button.action(), press, content.callbackLifetime())).toList();
        List<DialogBody> body = content.lines().stream()
                .map(line -> (DialogBody) DialogBody.plainMessage(Component.text(line))).toList();
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(content.title())).body(body)
                        .canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE).build())
                .type(DialogType.multiAction(buttons).columns(1)
                        .exitAction(button(text("shop.menu.close"), new ShopMenus.Action(ShopMenus.Kind.CLOSE),
                                press, content.callbackLifetime()))
                        .build())));
    }

    private ActionButton button(String label, ShopMenus.Action action, ShopMenus.Press press, Duration lifetime) {
        return ActionButton.builder(Component.text(label)).width(250)
                .action(DialogAction.customClick((response, audience) -> {
                    relay(audience, action, press);
                }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES)
                        .lifetime(lifetime).build())).build();
    }

    void relay(Audience audience, ShopMenus.Action action, ShopMenus.Press press) {
        if (audience instanceof Player player) {
            UUID id = player.getUniqueId();
            mainThread.execute(() -> {
                Player current = players.apply(id);
                if (current != null) { press.pressed(current, action); }
            });
        }
    }

    @Override
    public void close(Player player) { player.closeDialog(); }
}
