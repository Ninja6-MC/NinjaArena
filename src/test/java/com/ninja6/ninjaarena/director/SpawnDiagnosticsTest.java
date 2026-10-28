package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.director.SpawnPlacement.Refusal;
import com.ninja6.ninjaarena.director.SpawnPlacement.Refused;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class SpawnDiagnosticsTest {

    private final List<LogRecord> records = new ArrayList<>();
    private final FakeMatch match = new FakeMatch(List.of(List.of(new UUID(0, 1)), List.of(new UUID(0, 2)))).combat(0);
    private final Logger logger = Logger.getAnonymousLogger();

    SpawnDiagnosticsTest() {
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    private List<String> messages() {
        return records.stream().map(LogRecord::getMessage).toList();
    }

    @Test
    void namesTheCheckThatRefusedEachPoint() {
        var diagnostics = new SpawnDiagnostics(match, new SpawnSettings(100, true), logger);
        assertTrue(diagnostics.last().isEmpty());
        diagnostics.refused(EnemyKind.ZOMBIE, List.of(new Refused(1, Refusal.IN_VIEW),
                new Refused(2, Refusal.TOO_CLOSE), new Refused(3, Refusal.CHUNK_NOT_LOADED),
                new Refused(4, Refusal.BLOCKED)));
        assertEquals(List.of("Arena dojo, round 1, no enemy spawn point for a zombie: point 1 in view, point 2 too "
                + "close, point 3 chunk not loaded, point 4 blocked space."), messages());
        assertEquals(Level.INFO, records.get(0).getLevel());
        assertEquals("round 1, no enemy spawn point for a zombie: point 1 in view, point 2 too close, point 3 chunk "
                + "not loaded, point 4 blocked space", diagnostics.last().orElseThrow());
    }

    @Test
    void logsAtFineUnlessDebugIsOn() {
        var diagnostics = new SpawnDiagnostics(match, SpawnSettings.DEFAULTS, logger);
        diagnostics.refused(EnemyKind.SPIDER, List.of());
        assertEquals(List.of("Arena dojo, round 1, no enemy spawn point for a spider: the arena has no enemy spawn "
                + "points."), messages());
        assertEquals(Level.FINE, records.get(0).getLevel());
    }

    @Test
    void logsEachReasonOncePerRound() {
        var diagnostics = new SpawnDiagnostics(match, new SpawnSettings(100, true), logger);
        diagnostics.refused(EnemyKind.ZOMBIE, List.of(new Refused(1, Refusal.IN_VIEW)));
        diagnostics.refused(EnemyKind.SKELETON, List.of(new Refused(1, Refusal.IN_VIEW)));
        assertEquals(1, records.size(), "same reason, same round");
        assertEquals("round 1, no enemy spawn point for a skeleton: point 1 in view", diagnostics.last().orElseThrow(),
                "the status keeps the latest even when it is not logged");

        diagnostics.refused(EnemyKind.ZOMBIE, List.of(new Refused(1, Refusal.TOO_CLOSE)));
        assertEquals(2, records.size(), "a new reason is logged");
        diagnostics.refused(EnemyKind.ZOMBIE, List.of(new Refused(1, Refusal.IN_VIEW),
                new Refused(2, Refusal.TOO_CLOSE)));
        assertEquals(2, records.size(), "nothing new");

        match.nextRound();
        diagnostics.refused(EnemyKind.ZOMBIE, List.of(new Refused(1, Refusal.IN_VIEW)));
        assertEquals(3, records.size(), "logged again in the next round");
        assertTrue(messages().get(2).startsWith("Arena dojo, round 2, "), messages().get(2));
    }

    @Test
    void logsASpawnTheWorldCancelled() {
        var diagnostics = new SpawnDiagnostics(match, new SpawnSettings(100, true), logger);
        diagnostics.cancelled(EnemyKind.SKELETON, new Location(null, 10.5, 64, -3.25));
        diagnostics.cancelled(EnemyKind.SKELETON, new Location(null, 10.5, 64, -3.25));
        assertEquals(List.of("Arena dojo, round 1, the world cancelled a skeleton spawn at 10.5, 64.0, -3.3."),
                messages());
        assertEquals("round 1, the world cancelled a skeleton spawn at 10.5, 64.0, -3.3",
                diagnostics.last().orElseThrow());
    }
}
