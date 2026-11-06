package com.ninja6.ninjaarena.resourcepack;

import java.util.UUID;

/** Whether this connection has successfully loaded the Standard pack, rather than merely accepted it. */
@FunctionalInterface
public interface LoadedPacks {
    boolean loaded(UUID player);
}
