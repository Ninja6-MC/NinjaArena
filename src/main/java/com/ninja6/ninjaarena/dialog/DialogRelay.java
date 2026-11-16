package com.ninja6.ninjaarena.dialog;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/** Hands a dialog button press to the server thread without keeping the pressing player. */
public final class DialogRelay {
    private DialogRelay() {
    }

    /**
     * Runs {@code action} on {@code main} for the player who pressed. Dialog callbacks are not
     * guaranteed to run on the server thread, so only the UUID is read here; the player is looked
     * up when the task runs and the press is dropped if they are offline by then.
     */
    public static void onMain(Audience audience, Executor main, Function<UUID, Player> players,
            Consumer<Player> action) {
        if (audience instanceof Player presser) {
            UUID id = presser.getUniqueId();
            main.execute(() -> {
                Player current = players.apply(id);
                if (current != null) { action.accept(current); }
            });
        }
    }
}
