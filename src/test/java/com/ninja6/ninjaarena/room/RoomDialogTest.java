package com.ninja6.ninjaarena.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import net.kyori.adventure.text.event.ClickCallback;
import org.junit.jupiter.api.Test;

class RoomDialogTest {
    @Test void callbacksUseAnExplicitShortLifetimeWithUnlimitedUses() {
        assertEquals(Duration.ofMinutes(30), RoomDialog.CALLBACK_OPTIONS.lifetime());
        assertEquals(ClickCallback.UNLIMITED_USES, RoomDialog.CALLBACK_OPTIONS.uses());
    }
}
