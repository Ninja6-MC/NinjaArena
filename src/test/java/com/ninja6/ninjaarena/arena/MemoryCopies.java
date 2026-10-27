package com.ninja6.ninjaarena.arena;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Arena copies kept in memory, recording each call. Public so other packages' tests can use it. */
public final class MemoryCopies implements ArenaCopies {

    /** The bounds each copy was taken from, by arena name. */
    public final Map<String, ArenaBounds> saved = new HashMap<>();
    /** Every call, as {@code "save dojo"}, {@code "restore dojo"} or {@code "delete dojo"}. */
    public final List<String> calls = new ArrayList<>();
    /** When set, every call fails with an {@link IOException}. */
    public boolean failing;

    @Override
    public void save(String name, ArenaBounds bounds) throws IOException {
        calls.add("save " + name);
        fail();
        saved.put(name, bounds);
    }

    @Override
    public void restore(String name) throws IOException {
        calls.add("restore " + name);
        fail();
        if (!saved.containsKey(name)) {
            throw new IOException("there is no saved copy of " + name);
        }
    }

    @Override
    public boolean exists(String name) {
        return saved.containsKey(name);
    }

    @Override
    public void delete(String name) throws IOException {
        calls.add("delete " + name);
        fail();
        saved.remove(name);
    }

    private void fail() throws IOException {
        if (failing) {
            throw new IOException("disk full");
        }
    }
}
