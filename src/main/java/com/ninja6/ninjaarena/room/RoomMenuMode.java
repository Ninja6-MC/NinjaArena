package com.ninja6.ninjaarena.room;

import java.util.Locale;
import java.util.function.Consumer;

/** Configured room screen platform, read at startup independently of texture menus. */
public enum RoomMenuMode {
    DIALOG("dialog"), CHEST("chest");

    private final String label;

    RoomMenuMode(String label) {
        this.label = label;
    }

    public String label() { return label; }

    public static RoomMenuMode parse(Object raw, Consumer<String> warn) {
        if (raw == null) {
            return DIALOG;
        }
        if (raw instanceof String value) {
            for (RoomMenuMode mode : values()) {
                if (mode.label.equals(value.trim().toLowerCase(Locale.ROOT))) {
                    return mode;
                }
            }
        }
        warn.accept("rooms.menu must be dialog or chest, not '" + raw + "'. Using dialog.");
        return DIALOG;
    }
}
