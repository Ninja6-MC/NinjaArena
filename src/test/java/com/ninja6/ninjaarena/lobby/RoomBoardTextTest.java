package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.stream.IntStream;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class RoomBoardTextTest {
    private String render(List<RoomBoardText.Row> rows, int limit) {
        return PlainTextComponentSerializer.plainText().serialize(RoomBoardText.render(rows, limit));
    }

    @Test void emptyAndSingleRoomHaveReadableExplicitLines() {
        assertTrue(render(List.of(), 5).contains("No rooms"));
        String one = render(List.of(new RoomBoardText.Row("Alex", 1, 4, false, "dojo", "waiting")), 5);
        assertTrue(one.contains("Alex 1/4 Public\ndojo · Waiting"), one);
        assertFalse(one.contains("more rooms"));
    }

    @Test void statesPrivacyAndOverflowAreShownAndLongNamesAreBounded() {
        List<RoomBoardText.Row> rows = IntStream.range(0, 9)
                .mapToObj(i -> new RoomBoardText.Row("Host" + i, 2, 4, i == 1,
                        "map-name-that-is-much-too-long", List.of("waiting", "building", "playing").get(i % 3)))
                .toList();
        String many = render(rows, 3);
        assertTrue(many.contains("Waiting"));
        assertTrue(many.contains("Building"));
        assertTrue(many.contains("In match"));
        assertTrue(many.contains("Invite only"), many);
        assertTrue(many.contains("+6 more rooms\n"));
        assertFalse(many.contains("queued"));
        assertFalse(many.contains("Host3"));
        assertFalse(many.contains("much-too-long"));
    }
}
