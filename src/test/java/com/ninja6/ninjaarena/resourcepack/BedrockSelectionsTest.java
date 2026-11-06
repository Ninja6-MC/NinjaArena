package com.ninja6.ninjaarena.resourcepack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BedrockSelectionsTest {
    private final UUID standard = BedrockPackSettings.STANDARD_ID;
    private final UUID detailed = BedrockPackSettings.DETAILED_ID;
    private final UUID unrelated = UUID.randomUUID();
    private final BedrockSelections selections = new BedrockSelections(standard, detailed, true);

    private final class Wire implements BedrockSelections.Negotiator {
        final Set<UUID> offered = new LinkedHashSet<>(List.of(standard, detailed, unrelated));
        final List<Quality> choices = new ArrayList<>();
        boolean fail;
        @Override public void remove(UUID pack) { offered.remove(pack); }
        @Override public void offer(Quality quality) {
            if (fail) throw new IllegalArgumentException("invalid pack");
            choices.add(quality);
            offered.add(quality == Quality.STANDARD ? standard : detailed);
        }
    }

    @Test void defaultsToExactlyStandardAndPreservesUnrelatedPacks() {
        Wire wire = new Wire();
        Object connection = new Object();
        assertEquals(Quality.STANDARD, selections.negotiate(connection, "123456789", wire));
        assertEquals(Set.of(unrelated, standard), wire.offered);
        assertEquals(List.of(Quality.STANDARD), wire.choices);
        assertTrue(selections.selected(connection));
    }

    @Test void xuidLookupFreezesPerConnectionAndNextConnectionReadsNewPreference() {
        List<String> lookedUp = new ArrayList<>();
        selections.selector(xuid -> { lookedUp.add(xuid); return Quality.DETAILED; });
        Object first = new Object();
        Wire wire = new Wire();
        assertEquals(Quality.DETAILED, selections.negotiate(first, "123456789", wire));
        assertEquals(Set.of(unrelated, detailed), wire.offered);
        selections.selector(xuid -> Quality.STANDARD);
        assertEquals(Quality.DETAILED, selections.negotiate(first, "123456789", wire));
        assertEquals(List.of(Quality.DETAILED), wire.choices);
        assertEquals(List.of("123456789"), lookedUp);
        assertEquals(Quality.STANDARD, selections.negotiate(new Object(), "123456789", new Wire()));
    }

    @Test void missingIdentityNullOrFailedLookupAndUnavailableDetailedUseStandard() {
        selections.selector(xuid -> { throw new IllegalStateException("lookup unavailable"); });
        for (String identity : new String[] {null, "", "123456789"}) {
            assertEquals(Quality.STANDARD, selections.negotiate(new Object(), identity, new Wire()));
        }
        selections.selector(xuid -> null);
        assertEquals(Quality.STANDARD, selections.negotiate(new Object(), "123", new Wire()));
        var standardOnly = new BedrockSelections(standard, detailed, false);
        standardOnly.selector(xuid -> Quality.DETAILED);
        assertEquals(Quality.STANDARD, standardOnly.negotiate(new Object(), "123", new Wire()));
    }

    @Test void failedRegistrationDoesNotRecordASelectionAndCloseClearsState() {
        Object connection = new Object();
        Wire wire = new Wire();
        wire.fail = true;
        assertThrows(IllegalArgumentException.class, () -> selections.negotiate(connection, "123", wire));
        assertFalse(selections.selected(connection));
        wire.fail = false;
        selections.negotiate(connection, "123", wire);
        selections.clear();
        assertFalse(selections.selected(connection));
    }

    @Test void absentGeyserNeverInvokesTheOptionalFactoryOrLoadsItsClasses() throws Exception {
        assertSame(BedrockIntegration.NONE, BedrockSupport.start(false, () -> {
            throw new AssertionError("optional adapter must not be linked without Geyser");
        }));
        assertFalse(BedrockIntegration.NONE.isBedrock(UUID.randomUUID()));
        assertFalse(BedrockIntegration.NONE.customHeld(UUID.randomUUID()));
        assertTrue(BedrockIntegration.NONE.selections().isEmpty());
        // Geyser is compile-only: the entry point and core seams must link without it at runtime.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.geysermc.geyser.api.GeyserApi"));
        assertNotNull(Class.forName("com.ninja6.ninjaarena.NinjaArenaPlugin"));
        assertSame(BedrockIntegration.NONE, BedrockSupport.start(true, () -> BedrockIntegration.NONE));
    }
}
