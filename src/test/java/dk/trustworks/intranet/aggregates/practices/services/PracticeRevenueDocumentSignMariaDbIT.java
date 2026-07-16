package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Opt-in MariaDB contract that the fixed document sign persists against the V412
 * {@code chk_fpnri_document_sign CHECK (document_sign IS NULL OR document_sign IN (-1, 1))}.
 *
 * <p>The probe table reproduces the relevant columns and the exact document-sign CHECK from
 * {@code fact_practice_net_revenue_item_mat}. It proves that the rows the fixed
 * {@link PracticeRevenueMaterializationService#persist} produces persist: an external INVOICE and
 * PHANTOM carry {@code +1} even for a zero-value or negative-value item, an external CREDIT_NOTE
 * carries {@code -1} regardless of the item's own sign, and a genuine all-zero document persists.
 * It also proves the old item-sign derivation's {@code 0} is rejected by the constraint. The
 * configured schema must be disposable.</p>
 */
@EnabledIfEnvironmentVariable(named = "PRACTICES_COHERENCE_JDBC_URL", matches = ".+")
class PracticeRevenueDocumentSignMariaDbIT {

    private static final String TABLE = "fact_practice_net_revenue_item_mat_document_sign_probe";

    @Test
    void documentTypeDerivedSignsPersistAndZeroIsRejected() throws Exception {
        String url = System.getenv("PRACTICES_COHERENCE_JDBC_URL");
        String user = System.getenv().getOrDefault("PRACTICES_COHERENCE_JDBC_USER", "");
        String password = System.getenv().getOrDefault("PRACTICES_COHERENCE_JDBC_PASSWORD", "");
        createProbe(url, user, password);
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(true);

            // Invoice with a valid zero-value item: document sign +1, item control 0.00.
            insert(connection, "invoice-zero-item", "INVOICE", (short) 1,
                    "0.000000000000", "0.000000000000");
            // Invoice with a negative commercial adjustment: document sign stays +1.
            insert(connection, "invoice-negative-adjustment", "INVOICE", (short) 1,
                    "-100.000000000000", "-100.000000000000");
            // PHANTOM with a valid zero-value item: document sign +1.
            insert(connection, "phantom-zero-item", "PHANTOM", (short) 1,
                    "0.000000000000", "0.000000000000");
            // Credit reversal: document sign -1 even though the signed control is positive.
            insert(connection, "credit-reversal", "CREDIT_NOTE", (short) -1,
                    "-100.000000000000", "100.000000000000");
            // Genuine all-zero document: still persists with a valid +1 sign.
            insert(connection, "all-zero-document", "INVOICE", (short) 1,
                    "0.000000000000", "0.000000000000");

            assertEquals(5, count(connection),
                    "Every document-type derived sign row must satisfy chk_fpnri_document_sign");

            // The old item-value derivation could write a 0 for a valid zero line; the CHECK rejects it.
            assertThrows(SQLException.class, () -> insert(connection, "zero-sign-rejected", "INVOICE",
                    (short) 0, "0.000000000000", "0.000000000000"),
                    "document_sign 0 must be rejected by chk_fpnri_document_sign");
            assertEquals(5, count(connection));
        } finally {
            dropProbe(url, user, password);
        }
    }

    /**
     * When the configured schema is fully migrated, the same contract must hold on the live V412
     * table itself: the persisted CHECK clause is exactly the specified predicate, the five
     * type-derived sign rows insert, and a 0 sign is rejected. All writes roll back.
     */
    @Test
    void liveMigratedTableEnforcesTheSameDocumentSignContract() throws Exception {
        String url = System.getenv("PRACTICES_COHERENCE_JDBC_URL");
        String user = System.getenv().getOrDefault("PRACTICES_COHERENCE_JDBC_USER", "");
        String password = System.getenv().getOrDefault("PRACTICES_COHERENCE_JDBC_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    tableExists(connection, "fact_practice_net_revenue_item_mat"),
                    "schema is not migrated through V412; probe test covers this environment");
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS "
                            + "WHERE CONSTRAINT_SCHEMA = DATABASE() "
                            + "AND CONSTRAINT_NAME = 'chk_fpnri_document_sign'");
                 var result = query.executeQuery()) {
                org.junit.jupiter.api.Assertions.assertTrue(result.next(),
                        "chk_fpnri_document_sign must exist on the migrated schema");
                assertEquals("`document_sign` is null or `document_sign` in (-1,1)",
                        result.getString(1).toLowerCase().replaceAll("\\s+", " ").trim());
            }
            connection.setAutoCommit(false);
            try {
                insertInto(connection, "fact_practice_net_revenue_item_mat",
                        "live-invoice-zero-item", "INVOICE", (short) 1,
                        "0.000000000000", "0.000000000000");
                insertInto(connection, "fact_practice_net_revenue_item_mat",
                        "live-invoice-negative-adjustment", "INVOICE", (short) 1,
                        "-100.000000000000", "-100.000000000000");
                insertInto(connection, "fact_practice_net_revenue_item_mat",
                        "live-phantom-zero-item", "PHANTOM", (short) 1,
                        "0.000000000000", "0.000000000000");
                insertInto(connection, "fact_practice_net_revenue_item_mat",
                        "live-credit-reversal", "CREDIT_NOTE", (short) -1,
                        "-100.000000000000", "100.000000000000");
                insertInto(connection, "fact_practice_net_revenue_item_mat",
                        "live-all-zero-document", "INVOICE", (short) 1,
                        "0.000000000000", "0.000000000000");
                assertThrows(SQLException.class, () -> insertInto(connection,
                        "fact_practice_net_revenue_item_mat", "live-zero-sign-rejected", "INVOICE",
                        (short) 0, "0.000000000000", "0.000000000000"),
                        "document_sign 0 must be rejected by the live chk_fpnri_document_sign");
            } finally {
                connection.rollback();
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?")) {
            query.setString(1, table);
            try (var result = query.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private static void insert(Connection connection, String documentUuid, String documentType,
                               short documentSign, String nativeItemAmount,
                               String signedNativeControl) throws SQLException {
        insertInto(connection, TABLE, documentUuid, documentType, documentSign, nativeItemAmount,
                signedNativeControl);
    }

    private static void insertInto(Connection connection, String table, String documentUuid,
                                   String documentType, short documentSign, String nativeItemAmount,
                                   String signedNativeControl) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + table + " (generation_id, item_control_key, row_kind, "
                        + "source_document_uuid, source_item_uuid, company_uuid, source_document_type, "
                        + "source_document_status, recognized_month, native_item_amount, document_sign, "
                        + "signed_native_control, item_control_dkk, control_source, valuation_status, "
                        + "attribution_source_status, source_fingerprint) "
                        + "VALUES (?, ?, 'SOURCE_ITEM', ?, ?, 'co', ?, 'CREATED', '2026-02-01', ?, ?, ?, "
                        + "?, 'NATIVE_DKK', 'PROVISIONAL_NATIVE_DKK', 'UNASSIGNED', ?)")) {
            insert.setString(1, "gen-document-sign");
            insert.setString(2, "ITEM:" + documentUuid + ":item");
            insert.setString(3, documentUuid);
            insert.setString(4, documentUuid + "-item");
            insert.setString(5, documentType);
            insert.setBigDecimal(6, new java.math.BigDecimal(nativeItemAmount));
            insert.setShort(7, documentSign);
            insert.setBigDecimal(8, new java.math.BigDecimal(signedNativeControl));
            insert.setBigDecimal(9, new java.math.BigDecimal(signedNativeControl).setScale(2,
                    java.math.RoundingMode.HALF_UP));
            insert.setString(10, "f".repeat(64));
            insert.executeUpdate();
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM " + TABLE);
             var result = query.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void createProbe(String url, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute("CREATE TABLE " + TABLE + " ("
                    + "generation_id CHAR(36) NOT NULL,"
                    + "item_control_key VARCHAR(160) NOT NULL,"
                    + "row_kind ENUM('SOURCE_ITEM','DOCUMENT_RESIDUAL','DOCUMENT_EVIDENCE') NOT NULL,"
                    + "source_document_uuid VARCHAR(36) NOT NULL,"
                    + "source_item_uuid VARCHAR(40) NULL,"
                    + "company_uuid VARCHAR(36) NOT NULL,"
                    + "source_document_type VARCHAR(32) NOT NULL,"
                    + "source_document_status VARCHAR(32) NOT NULL,"
                    + "recognized_month DATE NOT NULL,"
                    + "native_item_amount DECIMAL(48,12) NULL,"
                    + "document_sign SMALLINT NULL,"
                    + "signed_native_control DECIMAL(48,12) NULL,"
                    + "item_control_dkk DECIMAL(48,12) NULL,"
                    + "control_source ENUM('ECONOMIC_GL','NATIVE_DKK','MONTHLY_FX','NONE') NOT NULL,"
                    + "valuation_status VARCHAR(64) NOT NULL,"
                    + "attribution_source_status VARCHAR(64) NOT NULL,"
                    + "source_fingerprint CHAR(64) NOT NULL,"
                    + "PRIMARY KEY (generation_id, item_control_key),"
                    // Mirrors V412 chk_fpnri_document_sign exactly.
                    + "CONSTRAINT chk_probe_document_sign CHECK (document_sign IS NULL "
                    + "OR document_sign IN (-1, 1))"
                    + ") ENGINE=InnoDB");
        }
    }

    private static void dropProbe(String url, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
        }
    }
}
