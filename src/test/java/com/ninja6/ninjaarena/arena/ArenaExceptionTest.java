package com.ninja6.ninjaarena.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.message.Keyed;
import com.ninja6.ninjaarena.message.Messages;
import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Refusals and arena problems are worded by messages.yml, never by the Java that raises them. */
class ArenaExceptionTest {

    private static final ArenaGrid GRID = new ArenaGrid(256, 2048, -64, 320);

    @AfterEach
    void bundled() {
        Messages.use(Messages.bundled());
    }

    private static void reword(String key, String text) {
        Map<String, Object> values = new LinkedHashMap<>(Messages.defaults());
        values.put(key, text);
        Messages.use(Messages.of(values, warning -> {
            throw new AssertionError(warning);
        }));
    }

    @Test
    void aRefusalNamesItsKeyAndReadsAsTheBundledDefault() {
        ArenaException refused = new ArenaException("arena.error.unknown", "arena", "dojo");
        assertEquals("arena.error.unknown", refused.key());
        assertEquals("No arena called dojo.", refused.getMessage());
        assertEquals("No arena called dojo.", PlainTextComponentSerializer.plainText().serialize(refused.text()));
    }

    @Test
    void aRewordedRefusalIsShownInTheServerWording() {
        ArenaException refused = new ArenaException("arena.error.unknown", "arena", "dojo");
        reword("arena.error.unknown", "Keine Arena namens <arena>.");
        assertEquals("Keine Arena namens dojo.", PlainTextComponentSerializer.plainText().serialize(refused.text()));
    }

    @Test
    void aModelRefusalKeepsItsLogTextAndShowsItsKey() {
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> Arena.create("dojo", new GridCell(0, 0)).withTeamSpawn(3, new ArenaPoint(0, 0, 0, 0, 0)));
        assertEquals("team must be 1 to 1", invalid.getMessage());
        ArenaException refused = ArenaException.refused(invalid);
        assertEquals("arena.invalid.team", refused.key());
        assertEquals("Team must be 1 to 1.", refused.getMessage());
        assertTrue(invalid instanceof Keyed);
    }

    @Test
    void problemLinesFollowMessagesYml() {
        Arena empty = Arena.create("dojo", new GridCell(0, 0));
        assertEquals("Bounds are not set.", ArenaValidator.problems(empty, GRID).get(0));
        reword("arena.problem.no-bounds", "Set the bounds first.");
        assertEquals("Set the bounds first.", ArenaValidator.problems(empty, GRID).get(0));
    }
}
