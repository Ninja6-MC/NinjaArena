package com.ninja6.ninjaarena.resourcepack.geyser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GeyserEnforcementTest {
    private static final String ENFORCED = "gameplay:\n  force-resource-packs: true\n";
    private final List<String> warnings = new ArrayList<>();
    private final Source source = new Source();

    private static final class Source implements GeyserConfigSource {
        byte[] bytes = ENFORCED.getBytes(StandardCharsets.UTF_8);
        IOException failure;
        int opens;
        int consumed;
        boolean closed;
        boolean failClose;
        @Override public Path file() { return Path.of("test-geyser", "config.yml"); }
        @Override public InputStream open() throws IOException {
            opens++;
            if (failure != null) throw failure;
            return new ByteArrayInputStream(bytes) {
                @Override public synchronized byte[] readNBytes(int count) throws IOException {
                    byte[] result = super.readNBytes(count);
                    consumed += result.length;
                    return result;
                }
                @Override public void close() throws IOException {
                    closed = true;
                    if (failClose) throw new IOException("close failed");
                }
            };
        }
    }

    private GeyserEnforcement gate(boolean declared) {
        return new GeyserEnforcement(declared, () -> source, warnings::add);
    }

    private void assertOneWarning() {
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains(source.file().toAbsolutePath().toString()));
        assertTrue(warnings.getFirst().contains("set gameplay.force-resource-packs: true in Geyser"));
        assertTrue(warnings.getFirst().contains("Bedrock held models disabled"));
    }

    @Test void requiredPolicyAndFileMustBothBeTrue() {
        var gate = gate(true);
        assertFalse(gate.allowed());
        gate.evaluate();
        assertTrue(gate.allowed());
        assertEquals(1, source.opens);
        assertTrue(source.closed);
        assertTrue(warnings.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gameplay: {force-resource-packs: false}", "gameplay: {}", "{}", "",
            "gameplay: {force-resource-packs: 'true'}", "gameplay.force-resource-packs: true",
            "gameplay: [", "gameplay: {force-resource-packs: true, force-resource-packs: false}",
            "gameplay: {force-resource-packs: false, force-resource-packs: true}"})
    void falseMissingOrMalformedSettingsFailClosed(String yaml) {
        source.bytes = yaml.getBytes(StandardCharsets.UTF_8);
        var gate = gate(true);
        gate.evaluate();
        for (int i = 0; i < 10; i++) assertFalse(gate.allowed());
        assertEquals(1, source.opens, "player checks must not read or warn");
        assertOneWarning();
    }

    @Test void missingOrUnreadableFileWarnsOncePerEvaluation() {
        var gate = gate(true);
        for (IOException failure : List.of(new NoSuchFileException("config.yml"), new IOException("access denied"))) {
            source.failure = failure;
            warnings.clear();
            gate.evaluate();
            assertFalse(gate.allowed());
            assertOneWarning();
        }
        assertEquals(2, source.opens);
    }

    @Test void oversizedInputIsBoundedAndClosedBeforeParsing() {
        source.bytes = (ENFORCED + "#".repeat(GeyserEnforcement.MAX_BYTES * 2)).getBytes(StandardCharsets.UTF_8);
        var gate = gate(true);
        gate.evaluate();
        assertFalse(gate.allowed());
        assertEquals(GeyserEnforcement.MAX_BYTES + 1, source.consumed);
        assertTrue(source.closed);
        assertOneWarning();
    }

    @Test void optionalPolicyDoesNotEvenLookUpGeyser() {
        var gate = new GeyserEnforcement(false, () -> {
            throw new AssertionError("must not look up a config source");
        }, warnings::add);
        gate.evaluate();
        assertFalse(gate.allowed());
        assertEquals(0, source.opens);
        assertTrue(warnings.isEmpty());
    }

    @Test void absentGeyserDoesNotAccessAFileOrWarn() {
        var gate = new GeyserEnforcement(true, () -> null, warnings::add);
        gate.evaluate();
        assertFalse(gate.allowed());
        assertEquals(0, source.opens);
        assertTrue(warnings.isEmpty());
    }

    @Test void reEvaluationFlipsTheGateAndNeverRetainsAStaleTrue() {
        var gate = gate(true);
        gate.evaluate();
        assertTrue(gate.allowed());
        source.bytes = "gameplay: {force-resource-packs: false}".getBytes(StandardCharsets.UTF_8);
        gate.evaluate();
        assertFalse(gate.allowed());
        assertOneWarning();
        source.bytes = ENFORCED.getBytes(StandardCharsets.UTF_8);
        gate.evaluate();
        assertTrue(gate.allowed());
        assertEquals(1, warnings.size());
        source.failure = new IOException("unreadable now");
        gate.evaluate();
        assertFalse(gate.allowed());
        assertEquals(2, warnings.size());
    }

    @Test void invalidUtf8AndCloseFailuresFailClosedWithoutLeakingConfigContents() {
        source.bytes = new byte[] {(byte) 0xc3, 0x28};
        var gate = gate(true);
        gate.evaluate();
        assertFalse(gate.allowed());
        warnings.clear();
        source.bytes = ENFORCED.getBytes(StandardCharsets.UTF_8);
        source.failClose = true;
        gate.evaluate();
        assertFalse(gate.allowed());
        assertOneWarning();
        warnings.clear();
        source.failClose = false;
        source.bytes = "secret-token: [do-not-log-this".getBytes(StandardCharsets.UTF_8);
        gate.evaluate();
        assertOneWarning();
        assertFalse(warnings.getFirst().contains("do-not-log-this"));
    }
}
