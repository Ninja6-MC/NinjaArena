package com.ninja6.ninjaarena.texture;

import com.ninja6.ninjaarena.message.Keyed;
import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/** Blocking storage of explicit texture choices. Only the store's own thread calls it. */
interface PreferenceBackend extends AutoCloseable {

    /** The database was written by a newer build; this one must leave it alone. */
    final class SchemaTooNew extends Exception implements Keyed {
        private final int found;
        private final int supported;

        SchemaTooNew(int found, int supported) {
            super("the texture preference schema is version " + found + " but this build supports up to "
                    + supported + "; update NinjaArena or point it at another database");
            this.found = found;
            this.supported = supported;
        }

        @Override
        public String key() {
            return "texture.problem.schema-too-new";
        }

        @Override
        public Object[] placeholders() {
            return new Object[] {"found", found, "supported", supported};
        }
    }

    Map<PlayerKey, Stored> loadAll() throws SQLException;

    Optional<Stored> load(PlayerKey key) throws SQLException;

    /** Commits the choice and returns what is now stored. */
    Stored save(PlayerKey key, Quality quality, long now) throws SQLException;

    @Override
    void close();
}
