package com.ninja6.ninjaarena.lobby;

import com.ninja6.ninjaarena.room.Room;
import com.ninja6.ninjaarena.room.RoomSettings;

/**
 * What a room's pad shows, in its fog colour and its label. A room in a match is
 * {@link #PLAYING} whatever else holds; then a full room is {@link #FULL}; otherwise its
 * privacy decides.
 */
public enum PadState {
    /** Public with space: standing on the pad joins. */
    OPEN("open"),
    /** Ask to join: standing on the pad sends a join request. */
    ASK("ask"),
    /** Invite only: standing on the pad joins invited players only. */
    INVITE("invite"),
    /** No space left. */
    FULL("full"),
    /** Its match runs; nobody joins from the pad. */
    PLAYING("playing");

    private final String key;

    PadState(String key) {
        this.key = key;
    }

    /** Its name in config.yml and messages.yml. */
    public String key() {
        return key;
    }

    public static PadState of(Room room) {
        if (room.isPlaying()) {
            return PLAYING;
        }
        if (room.isFull()) {
            return FULL;
        }
        RoomSettings.Privacy privacy = room.settings().privacy();
        return switch (privacy) {
            case PUBLIC -> OPEN;
            case ASK -> ASK;
            case INVITE -> INVITE;
        };
    }
}
