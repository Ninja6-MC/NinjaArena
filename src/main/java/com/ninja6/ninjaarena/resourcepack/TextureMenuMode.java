package com.ninja6.ninjaarena.resourcepack;

import java.util.Locale;
import java.util.function.Consumer;

/** Which screen {@code /ninjaarena textures} opens: a native dialog or the chest menu. */
public enum TextureMenuMode {
    DIALOG("dialog"),
    CHEST("chest");

    public static final String KEY = "resource-pack.texture-menu";

    private final String label;

    TextureMenuMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** A missing value is {@link #DIALOG}; an unknown one is {@link #DIALOG} with a warning. */
    public static TextureMenuMode parse(Object raw, Consumer<String> warn) {
        if (raw == null) {
            return DIALOG;
        }
        if (raw instanceof String text) {
            String value = text.trim().toLowerCase(Locale.ROOT);
            for (TextureMenuMode mode : values()) {
                if (mode.label.equals(value)) {
                    return mode;
                }
            }
        }
        warn.accept(KEY + " must be dialog or chest, not '" + raw + "'. Using dialog.");
        return DIALOG;
    }
}
