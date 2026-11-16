package com.ninja6.ninjaarena.lobby;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Reference counts board tickets: Paper holds only one ticket per plugin and chunk. */
public final class BoardTickets {
    private record Chunk(int x, int z) { }
    private final Map<Chunk, Integer> held = new HashMap<>();
    private final BiConsumer<Integer, Integer> add;
    private final BiConsumer<Integer, Integer> remove;

    public BoardTickets(BiConsumer<Integer, Integer> add, BiConsumer<Integer, Integer> remove) {
        this.add = add;
        this.remove = remove;
    }

    public void acquire(int x, int z) {
        if (held.merge(new Chunk(x, z), 1, Integer::sum) == 1) {
            add.accept(x, z);
        }
    }

    public void release(int x, int z) {
        Chunk chunk = new Chunk(x, z);
        Integer count = held.get(chunk);
        if (count == null) {
            return;
        }
        if (count == 1) {
            held.remove(chunk);
            remove.accept(x, z);
        } else {
            held.put(chunk, count - 1);
        }
    }
}
