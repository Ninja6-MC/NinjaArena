package com.ninja6.ninjaarena.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.support.TestTaggedItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NinjaToolTest {

    @TempDir
    Path dir;

    @Test
    void toolsAreRecognisedByTheirDataTagOnly() {
        for (NinjaTool tool : NinjaTool.values()) {
            var item = new TestTaggedItem(tool.material(), Map.of(NinjaTool.KEY, tool.key()), 1);
            assertEquals(Optional.of(tool), NinjaTool.of(item));
        }
        assertEquals(Optional.empty(), NinjaTool.of(new TestTaggedItem(Material.SNOWBALL, Map.of(), 1)));
        assertEquals(Optional.empty(), NinjaTool.of(new TestTaggedItem(Material.SNOWBALL,
                Map.of(NinjaTool.KEY, "katana"), 1)));
        assertEquals(Optional.empty(), NinjaTool.of(new TestTaggedItem(Material.FEATHER,
                Map.of(new NamespacedKey("other", "tool"), "dash"), 1)));
        assertEquals(Optional.empty(), NinjaTool.of(new TestTaggedItem(Material.FEATHER,
                Map.of(NinjaTool.KEY, "dash"), 0)));
        assertEquals(Optional.empty(), NinjaTool.of(null));
    }

    @Test
    void everyToolRegistersItsKitId() {
        var items = new KitItems();
        NinjaTools.registerItems(items);
        assertEquals(List.of("ninjaarena:smoke-bomb", "ninjaarena:shuriken", "ninjaarena:dash"),
                List.of(NinjaTool.values()).stream().map(NinjaTool::kitId).toList());
        for (NinjaTool tool : NinjaTool.values()) {
            var duplicate = assertThrows(IllegalArgumentException.class,
                    () -> items.register(tool.kitId(), player -> null));
            assertTrue(duplicate.getMessage().contains("already registered"));
        }
    }

    @Test
    void aKitTurnsAToolOffByLeavingItsEntryOut() throws Exception {
        Path file = dir.resolve("kits.yml");
        Files.writeString(file, """
                format: 1
                items:
                  - slot: 3
                    id: ninjaarena:dash
                  - slot: 1
                    id: ninjaarena:shuriken
                    amount: 4
                """);
        var kit = KitDefinition.load(file, Logger.getAnonymousLogger());
        assertEquals(List.of(new KitDefinition.Entry(3, null, "ninjaarena:dash", 1),
                new KitDefinition.Entry(1, null, "ninjaarena:shuriken", 4)), kit.items());
    }
}
