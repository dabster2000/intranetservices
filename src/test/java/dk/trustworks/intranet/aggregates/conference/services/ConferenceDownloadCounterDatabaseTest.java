package dk.trustworks.intranet.aggregates.conference.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in against a new, disposable local MariaDB database only. Never uses the application's datasource.
 * Run with -Dconference.downloads.test.jdbc-url=jdbc:mariadb://127.0.0.1:PORT/conference_downloads_test
 * and -Dconference.downloads.test.password=the-disposable-container-password.
 */
@EnabledIfSystemProperty(named = "conference.downloads.test.jdbc-url",
        matches = "jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/conference_downloads_test")
class ConferenceDownloadCounterDatabaseTest {
    private Connection connect() throws Exception {
        return DriverManager.getConnection(System.getProperty("conference.downloads.test.jdbc-url"),
                "root", System.getProperty("conference.downloads.test.password"));
    }

    @Test
    void migrationKeepsOnlyAggregatesAndConcurrentRequestsDoNotLoseIncrements() throws Exception {
        try (var connection = connect(); var statement = connection.createStatement()) {
            // Deliberately not IF NOT EXISTS: refuse an already populated test database.
            statement.execute(Files.readString(Path.of(
                    "src/main/resources/db/migration/V616__Conference_presentation_download_counts.sql")));
            try (var columns = statement.executeQuery("SHOW COLUMNS FROM conference_presentation_downloads")) {
                List<String> names = new ArrayList<>();
                while (columns.next()) names.add(columns.getString("Field"));
                assertEquals(List.of("conference_uuid", "presentation_id", "download_requests"), names);
            }
        }

        int writers = 8;
        int incrementsPerWriter = 40;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        String jdbcSql = ConferenceDownloadService.INCREMENT_SQL
                .replace(":conference", "?").replace(":presentation", "?");
        try (var pool = Executors.newFixedThreadPool(writers)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (int writer = 0; writer < writers; writer++) {
                futures.add(pool.submit(() -> {
                    try (var connection = connect(); var insert = connection.prepareStatement(jdbcSql)) {
                        insert.setString(1, ConferenceDownloadCatalog.CONFERENCE_UUID);
                        insert.setString(2, "test-new-black");
                        ready.countDown();
                        assertTrue(start.await(15, TimeUnit.SECONDS));
                        for (int request = 0; request < incrementsPerWriter; request++) insert.executeUpdate();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }

        try (var connection = connect(); var insert = connection.prepareStatement(jdbcSql)) {
            insert.setString(1, ConferenceDownloadCatalog.CONFERENCE_UUID);
            insert.setString(2, "fart-kontrol");
            insert.executeUpdate();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT presentation_id, download_requests FROM conference_presentation_downloads ORDER BY presentation_id")) {
                assertTrue(rows.next());
                assertEquals("fart-kontrol", rows.getString(1));
                assertEquals(1, rows.getLong(2));
                assertTrue(rows.next());
                assertEquals("test-new-black", rows.getString(1));
                assertEquals((long) writers * incrementsPerWriter, rows.getLong(2));
                assertFalse(rows.next());
            }
        }
    }
}
