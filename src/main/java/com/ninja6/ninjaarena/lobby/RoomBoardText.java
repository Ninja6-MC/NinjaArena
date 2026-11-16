package com.ninja6.ninjaarena.lobby;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.room.RoomSettings.Privacy;
import java.util.List;
import net.kyori.adventure.text.Component;

/** Explicit lines, including on Bedrock where display line widths are ignored. */
public final class RoomBoardText {
    public record Row(String host, int players, int needed, Privacy privacy, String map, String state) { }

    private RoomBoardText() { }

    public static Component render(List<Row> rooms, int limit) {
        Component result = msg("lobby.boards.list-title");
        if (rooms.isEmpty()) {
            result = result.append(Component.newline()).append(msg("lobby.boards.empty"));
        }
        for (Row room : rooms.subList(0, Math.min(rooms.size(), Math.max(1, limit)))) {
            result = result.append(Component.newline()).append(msg("lobby.boards.room",
                    "host", shortName(room.host()), "players", room.players(), "needed", room.needed(),
                    "privacy", msg(privacyKey(room.privacy())),
                    "map", shortName(room.map()), "state", msg("lobby.boards." + room.state())));
        }
        if (rooms.size() > Math.max(1, limit)) {
            result = result.append(Component.newline()).append(msg("lobby.boards.more",
                    "count", rooms.size() - Math.max(1, limit)));
        }
        return result.append(Component.newline()).append(msg("lobby.boards.list-click"));
    }

    private static String privacyKey(Privacy privacy) {
        return switch (privacy) {
            case PUBLIC -> "room.screen.public";
            case ASK -> "room.screen.ask";
            case INVITE -> "room.screen.private";
        };
    }

    private static String shortName(String value) {
        return value.length() <= 20 ? value : value.substring(0, 19) + "…";
    }
}
