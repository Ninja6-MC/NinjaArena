package com.ninja6.ninjaarena.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import net.kyori.adventure.text.event.ClickCallback;
import org.junit.jupiter.api.Test;

class TexturePaperScreensTest {
    @Test void callbacksUseAnExplicitShortLifetimeWithUnlimitedUses() {
        assertEquals(Duration.ofMinutes(10), TextureDialog.PaperScreens.CALLBACK_OPTIONS.lifetime());
        assertEquals(ClickCallback.UNLIMITED_USES, TextureDialog.PaperScreens.CALLBACK_OPTIONS.uses());
    }
}
