package com.ninja6.ninjaarena.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import com.ninja6.ninjaarena.stats.ConnectionSource;
import com.ninja6.ninjaarena.stats.StatsSettings.Storage;
import com.ninja6.ninjaarena.texture.PreferenceBackend.SchemaTooNew;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The MySQL statements run in H2's MySQL compatibility mode. This is NOT a MySQL or MariaDB
 * server: it checks the SQL text and the schema logic only, and says nothing about a real
 * server's types, collations, locking or driver behaviour.
 */
@DisplayName("H2 MySQL-compatibility mode (not a real MySQL server)")
class PreferenceMySqlCompatH2Test {

    private static final AtomicInteger NAMES = new AtomicInteger();
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final String url = "jdbc:h2:mem:prefs" + NAMES.incrementAndGet()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private final ConnectionSource source = () -> DriverManager.getConnection(url);

    @Test
    void savesUpdatesAndReloadsThroughTheMySqlStatements() throws Exception {
        PlayerKey key = PlayerKey.java(ALICE);
        try (SqlPreferenceBackend first = SqlPreferenceBackend.open(source, Storage.MYSQL, "na_")) {
            assertEquals(1, first.save(key, Quality.DETAILED, 10).revision());
            Stored again = first.save(key, Quality.STANDARD, 20);
            assertEquals(2, again.revision());
            assertEquals(Quality.STANDARD, again.quality());
            first.save(PlayerKey.bedrock("77"), Quality.DETAILED, 30);
        }
        try (SqlPreferenceBackend second = SqlPreferenceBackend.open(source, Storage.MYSQL, "na_")) {
            assertEquals(Quality.STANDARD, second.load(key).orElseThrow().quality());
            assertEquals(2, second.loadAll().size());
            assertEquals(20, second.load(key).orElseThrow().updatedAt());
        }
    }

    @Test
    void schemaVersionIsSeparateAndANewerOneIsRefused() throws Exception {
        SqlPreferenceBackend.open(source, Storage.MYSQL, "na_").close();
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE na_texture_schema SET version = 7 WHERE id = 1");
        }
        assertThrows(SchemaTooNew.class, () -> SqlPreferenceBackend.open(source, Storage.MYSQL, "na_"));
    }
}
