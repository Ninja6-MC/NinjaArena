package com.ninja6.ninjaarena.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ninja6.ninjaarena.support.TestItemStack;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class KitItemsTest {
    @Test
    void customProvidersAreResolvedLateAndTheirReturnedStacksAreCloned() {
        var entry = new KitDefinition.Entry(0, null, "test:tool", 3);
        var items = new KitItems();
        assertThrows(IllegalArgumentException.class, () -> items.create(entry, null));
        var template = new TestItemStack(Material.PAPER, "custom-data", 1);
        items.register("test:tool", player -> template);
        var first = (TestItemStack) items.create(entry, null);
        var second = (TestItemStack) items.create(entry, null);
        assertEquals("custom-data", first.marker());
        assertEquals(3, first.getAmount());
        assertEquals(1, template.getAmount());
        assertNotSame(first, template);
        assertNotSame(first, second);
        first.setAmount(1);
        assertEquals(3, second.getAmount());
    }

    @Test
    void invalidOrDuplicateProviderIdsAndInvalidStacksAreRejected() {
        var items = new KitItems();
        assertThrows(IllegalArgumentException.class, () -> items.register("bad", player -> null));
        items.register("test:empty", player -> null);
        assertThrows(IllegalArgumentException.class, () -> items.register("test:empty", player -> null));
        assertThrows(IllegalArgumentException.class,
                () -> items.create(new KitDefinition.Entry(0, null, "test:empty", 1), null));
        items.register("test:stack", player -> new TestItemStack(Material.STONE, "item", 1));
        assertThrows(IllegalArgumentException.class,
                () -> items.create(new KitDefinition.Entry(0, null, "test:stack", 99), null));
    }

    @Test
    void vanillaEntriesUseTheMaterialFactoryAndAlsoProduceIndependentStacks() {
        var items = new KitItems(material -> new TestItemStack(material, "vanilla", 1));
        var entry = new KitDefinition.Entry(7, Material.ARROW, null, 16);
        var stack = items.create(entry, null);
        assertEquals(Material.ARROW, stack.getType());
        assertEquals(16, stack.getAmount());
    }
}
