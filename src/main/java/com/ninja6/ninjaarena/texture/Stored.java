package com.ninja6.ninjaarena.texture;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import java.util.Objects;

/**
 * One stored explicit choice. {@code revision} rises by one on every write of the same
 * identity, and {@code updatedAt} is the write time in epoch milliseconds.
 */
public record Stored(Quality quality, long revision, long updatedAt) {
    public Stored {
        Objects.requireNonNull(quality, "quality");
    }
}
