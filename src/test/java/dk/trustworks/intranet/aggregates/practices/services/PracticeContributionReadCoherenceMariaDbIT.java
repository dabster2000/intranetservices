package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in MariaDB contract for the request-time coherence transaction shape.
 * The configured schema must be disposable; this test creates and drops one isolated probe table.
 */
@EnabledIfEnvironmentVariable(named = "PRACTICES_COHERENCE_JDBC_URL", matches = ".+")
class PracticeContributionReadCoherenceMariaDbIT {

    private static final String TABLE = "practice_contribution_source_watermark_rr_probe";

    @Test
    void freshAfterTransactionObservesCommitThatTheBodyRepeatableReadSnapshotCannot() throws Exception {
        String url = System.getenv("PRACTICES_COHERENCE_JDBC_URL");
        String user = System.getenv().getOrDefault("PRACTICES_COHERENCE_JDBC_USER", "");
        String password = System.getenv().getOrDefault("PRACTICES_COHERENCE_JDBC_PASSWORD", "");
        createProbe(url, user, password);

        CountDownLatch bodyRead = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Watermark>> body = executor.submit(() -> {
                try (Connection connection = connection(url, user, password)) {
                    Watermark before = readWatermark(connection);
                    bodyRead.countDown();
                    assertTrue(writerCommitted.await(5, TimeUnit.SECONDS),
                            "Concurrent publication did not commit in time");
                    Watermark repeated = readWatermark(connection);
                    connection.commit();
                    return List.of(before, repeated);
                }
            });
            Future<?> writer = executor.submit(() -> {
                assertTrue(bodyRead.await(5, TimeUnit.SECONDS), "Body read did not start in time");
                try (Connection connection = connection(url, user, password);
                     PreparedStatement update = connection.prepareStatement(
                             "UPDATE " + TABLE + " SET source_version=2, source_state='READY_V2' "
                                     + "WHERE watermark_id=1")) {
                    assertEquals(1, update.executeUpdate());
                    connection.commit();
                    writerCommitted.countDown();
                }
                return null;
            });

            assertEquals(List.of(new Watermark(1, "READY_V1"), new Watermark(1, "READY_V1")),
                    body.get(10, TimeUnit.SECONDS),
                    "One REPEATABLE READ body transaction must keep one coherent watermark snapshot");
            writer.get(10, TimeUnit.SECONDS);

            try (Connection afterConnection = connection(url, user, password)) {
                assertEquals(new Watermark(2, "READY_V2"), readWatermark(afterConnection),
                        "A fresh after transaction must observe the concurrent committed watermark");
                afterConnection.commit();
            }
        } finally {
            executor.shutdownNow();
            dropProbe(url, user, password);
        }
    }

    private static Connection connection(String url, String user, String password) throws SQLException {
        Connection connection = DriverManager.getConnection(url, user, password);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        return connection;
    }

    private static Watermark readWatermark(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT source_version, source_state FROM " + TABLE + " WHERE watermark_id=1")) {
            query.setQueryTimeout(5);
            try (var result = query.executeQuery()) {
                assertTrue(result.next());
                return new Watermark(result.getInt(1), result.getString(2));
            }
        }
    }

    private static void createProbe(String url, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute("CREATE TABLE " + TABLE + " (watermark_id INT PRIMARY KEY, "
                    + "source_version INT NOT NULL, source_state VARCHAR(64) NOT NULL) ENGINE=InnoDB");
            statement.executeUpdate("INSERT INTO " + TABLE
                    + " (watermark_id, source_version, source_state) VALUES (1, 1, 'READY_V1')");
        }
    }

    private static void dropProbe(String url, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
        }
    }

    private record Watermark(int sourceVersion, String sourceState) {}
}
