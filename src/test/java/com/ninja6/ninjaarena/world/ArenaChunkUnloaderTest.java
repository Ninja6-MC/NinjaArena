package com.ninja6.ninjaarena.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.Chunk.LoadLevel;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArenaChunkUnloaderTest {

    /** Loaded chunks by {@code "x,z"}, with their load level. */
    private final Map<String, LoadLevel> loaded = new LinkedHashMap<>();
    /** Every unload call, as {@code "x,z save"} or {@code "x,z discard"}. */
    private final List<String> unloads = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean saving;
    /** When set, an unload leaves the chunk loaded. */
    private boolean refuse;
    private ArenaChunkUnloader unloader;

    @BeforeEach
    void open() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        unloader = new ArenaChunkUnloader(world(), () -> saving, logger);
    }

    private World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> ArenaWorld.NAME;
                    case "getLoadedChunks" -> loaded.keySet().stream().map(this::chunk).toArray(Chunk[]::new);
                    case "unloadChunk" -> {
                        String key = args[0] + "," + args[1];
                        unloads.add(key + ((Boolean) args[2] ? " save" : " discard"));
                        if (!refuse) {
                            loaded.remove(key);
                        }
                        yield !loaded.containsKey(key);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Chunk chunk(String key) {
        String[] xz = key.split(",");
        LoadLevel level = loaded.get(key);
        return (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[] {Chunk.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getX" -> Integer.parseInt(xz[0]);
                    case "getZ" -> Integer.parseInt(xz[1]);
                    case "getLoadLevel" -> level;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void unloadsOnlyTicketlessChunksWithoutSavingThem() {
        loaded.put("0,0", LoadLevel.ENTITY_TICKING);
        loaded.put("1,0", LoadLevel.INACCESSIBLE);
        loaded.put("2,0", LoadLevel.BORDER);
        loaded.put("3,0", LoadLevel.TICKING);
        loaded.put("-4,7", LoadLevel.INACCESSIBLE);

        assertEquals(2, unloader.sweep());

        assertEquals(List.of("1,0 discard", "-4,7 discard"), unloads);
        assertEquals(List.of("0,0", "2,0", "3,0"), List.copyOf(loaded.keySet()));
        assertEquals(2, unloader.unloaded());
        assertEquals(List.of(), warnings);
    }

    @Test
    void unloadsNothingWhileWorldSavingIsOn() {
        loaded.put("1,0", LoadLevel.INACCESSIBLE);
        saving = true;

        assertEquals(0, unloader.sweep());
        assertEquals(List.of(), unloads);

        saving = false;
        assertEquals(1, unloader.sweep());
        assertEquals(List.of("1,0 discard"), unloads);
    }

    @Test
    void spreadsALargeBacklogOverSweeps() {
        int backlog = ArenaChunkUnloader.MAX_PER_SWEEP + 10;
        for (int x = 0; x < backlog; x++) {
            loaded.put(x + ",0", LoadLevel.INACCESSIBLE);
        }

        assertEquals(ArenaChunkUnloader.MAX_PER_SWEEP, unloader.sweep());
        assertEquals(10, loaded.size());
        assertEquals(10, unloader.sweep());
        assertTrue(loaded.isEmpty());
        assertEquals(backlog, unloader.unloaded());
    }

    @Test
    void aRefusedUnloadClearsTheNoSaveMarkAndStopsSweeping() {
        loaded.put("1,0", LoadLevel.INACCESSIBLE);
        loaded.put("2,0", LoadLevel.INACCESSIBLE);
        refuse = true;

        assertEquals(0, unloader.sweep());

        // The second request, with saving allowed, clears the mark the first one left.
        assertEquals(List.of("1,0 discard", "1,0 save"), unloads);
        assertTrue(unloader.isStopped());
        assertEquals(1, warnings.size());

        refuse = false;
        assertEquals(0, unloader.sweep());
        assertEquals(2, unloads.size());
    }

    @Test
    void startsUnstopped() {
        assertFalse(unloader.isStopped());
        assertEquals(0, unloader.sweep());
    }
}
