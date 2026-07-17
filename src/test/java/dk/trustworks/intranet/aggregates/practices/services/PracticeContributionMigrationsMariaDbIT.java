package dk.trustworks.intranet.aggregates.practices.services;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Opt-in disposable MariaDB integration harness. The supplied schema must be
 * empty and disposable; the test intentionally never targets a configured
 * application datasource. It pauses at V410, applies V411/V412, then every later
 * migration, and restarts Flyway to prove repeatable validation against the same
 * schema history.
 */
@EnabledIfEnvironmentVariable(named = "PRACTICES_MIGRATION_JDBC_URL", matches = ".+")
class PracticeContributionMigrationsMariaDbIT {

    @Test
    void migratesFromTheV410PredecessorAndValidatesAfterRestart() throws Exception {
        String url = System.getenv("PRACTICES_MIGRATION_JDBC_URL");
        String user = System.getenv().getOrDefault("PRACTICES_MIGRATION_JDBC_USER", "");
        String password = System.getenv().getOrDefault("PRACTICES_MIGRATION_JDBC_PASSWORD", "");

        createV410PredecessorFixture(url, user, password);

        Flyway throughV412 = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("410")
                .target("412")
                .load();
        throughV412.migrate();
        throughV412.validate();

        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO fact_change_log(
                        useruuid,affected_date,change_type,source_table,source_id,processed_at)
                    VALUES('user-after-v412','2026-07-15','WORK','work','work-after-v412',UTC_TIMESTAMP(6))
                    """);
        }

        Flyway current = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .load();
        current.migrate();
        current.validate();

        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                       FROM information_schema.tables
                      WHERE table_schema = DATABASE()
                        AND table_name IN (
                          'practice_basis_generation',
                          'practice_cost_basis_refresh_request',
                          'fact_practice_net_revenue_item_mat',
                          'fact_practice_net_revenue_allocation_mat',
                          'practice_revenue_publication'
                        )
                     """)) {
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals(5, result.getInt(1));
            }
        }

        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT w.last_fact_change_log_id=COALESCE((SELECT MAX(id) FROM fact_change_log),0),
                            w.last_pruned_fact_change_log_id<=w.last_fact_change_log_id,
                            p.booked_available,p.booked_reason,
                            p.booked_plus_draft_available,p.booked_plus_draft_reason
                     FROM practice_revenue_source_watermark w
                     JOIN practice_operating_cost_publication p ON p.publication_id=1
                     WHERE w.source_name='DELIVERY_EVIDENCE'
                     """)) {
            result.next();
            assertEquals(1, result.getInt(1));
            assertEquals(1, result.getInt(2));
            assertEquals(0, result.getInt(3));
            assertEquals("SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW", result.getString(4));
            assertEquals(0, result.getInt(5));
            assertEquals("SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW", result.getString(6));
        }

        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO accounting_accounts(uuid, companyuuid, account_code, cost_type)
                    VALUES ('account-1', 'company-1', 1000, 'OPEX')
                    """);
            try (var result = statement.executeQuery("""
                    SELECT r.cause, p.latest_cost_basis_request_id=r.request_id,
                           p.latest_cost_basis_request_vector=r.input_vector_fingerprint
                    FROM practice_operating_cost_publication p
                    JOIN practice_cost_basis_refresh_request r
                      ON r.request_id=p.latest_cost_basis_request_id
                    WHERE p.publication_id=1
                    """)) {
                result.next();
                assertEquals("COST_GL_INPUT", result.getString(1));
                assertEquals(1, result.getInt(2));
                assertEquals(1, result.getInt(3));
            }

            statement.executeUpdate("""
                    INSERT INTO user_practice_history(
                        uuid, useruuid, practice, effective_from, effective_to)
                    VALUES ('history-1', 'user-1', 'DEV', '2026-01-01', NULL)
                    """);
            try (var result = statement.executeQuery("""
                    SELECT r.cause, p.latest_cost_basis_request_id=r.request_id,
                           p.latest_cost_basis_request_vector=r.input_vector_fingerprint
                    FROM practice_operating_cost_publication p
                    JOIN practice_cost_basis_refresh_request r
                      ON r.request_id=p.latest_cost_basis_request_id
                    WHERE p.publication_id=1
                    """)) {
                result.next();
                assertEquals("PRACTICE_BASIS_INPUT", result.getString(1));
                assertEquals(1, result.getInt(2));
                assertEquals(1, result.getInt(3));
            }
        }

        Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration").load().validate();
    }

    private static void createV410PredecessorFixture(
            String url, String user, String password) throws SQLException {
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            for (String ddl : new String[]{
                    "CREATE TABLE bi_refresh_watermark (pipeline_name VARCHAR(64) PRIMARY KEY, "
                            + "certified_complete_through_date DATE NULL, last_full_refresh_at DATETIME(6) NULL, "
                            + "last_incremental_refresh_at DATETIME(6) NULL, refresh_state VARCHAR(20) NOT NULL, "
                            + "active_refresh_token CHAR(36) NULL)",
                    "INSERT INTO bi_refresh_watermark VALUES "
                            + "('FACT_USER_DAY', CURRENT_DATE, UTC_TIMESTAMP(6), NULL, 'READY', NULL)",
                    "CREATE TABLE practice_operating_cost_publication (publication_id TINYINT PRIMARY KEY, "
                            + "refresh_state VARCHAR(20) NOT NULL, active_refresh_token CHAR(36) NULL, "
                            + "generation_at DATETIME(6) NULL, published_at DATETIME(6) NULL, "
                            + "opex_row_count BIGINT NOT NULL, fte_row_count BIGINT NOT NULL, "
                            + "completeness_row_count BIGINT NOT NULL, last_failure_at DATETIME(6) NULL)",
                    "INSERT INTO practice_operating_cost_publication VALUES "
                            + "(1, 'READY', NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1, 1, 1, NULL)",
                    "CREATE TABLE user_practice_history (uuid VARCHAR(36) PRIMARY KEY, "
                            + "useruuid VARCHAR(36) NOT NULL, practice VARCHAR(50) NOT NULL, "
                            + "effective_from DATE NOT NULL, effective_to DATE NULL)",
                    "CREATE TABLE userstatus (uuid VARCHAR(36) PRIMARY KEY, useruuid VARCHAR(36), "
                            + "type VARCHAR(32), status VARCHAR(32), statusdate DATE, allocation INT, "
                            + "companyuuid VARCHAR(36))",
                    "CREATE TABLE accounting_accounts (uuid VARCHAR(36) PRIMARY KEY, "
                            + "companyuuid VARCHAR(36), account_code INT, cost_type VARCHAR(20))",
                    "CREATE TABLE fact_change_log (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, "
                            + "useruuid VARCHAR(36), affected_date DATE, change_type VARCHAR(32), "
                            + "source_table VARCHAR(64), source_id VARCHAR(40), processed_at DATETIME NULL)",
                    "CREATE TABLE invoice_item_attributions (uuid VARCHAR(36) PRIMARY KEY, "
                            + "invoiceitem_uuid VARCHAR(40), consultant_uuid VARCHAR(36), "
                            + "share_pct DECIMAL(7,4), attributed_amount DECIMAL(12,2), source VARCHAR(30))",
                    "CREATE TABLE invoiceitems (uuid VARCHAR(40) PRIMARY KEY, source_item_uuid VARCHAR(40) NULL)",
                    "CREATE TABLE currences (currency VARCHAR(8), month CHAR(6), conversion DOUBLE, "
                            + "PRIMARY KEY (currency, month))",
                    "CREATE TABLE task (uuid VARCHAR(36) PRIMARY KEY)",
                    "CREATE TABLE project (uuid VARCHAR(36) PRIMARY KEY)",
                    "CREATE TABLE contract_project (uuid VARCHAR(36) PRIMARY KEY, "
                            + "contractuuid VARCHAR(36), projectuuid VARCHAR(36))",
                    "CREATE TABLE contracts (uuid VARCHAR(36) PRIMARY KEY)",
                    "CREATE TABLE contract_consultants (uuid VARCHAR(36) PRIMARY KEY, "
                            + "contractuuid VARCHAR(36), useruuid VARCHAR(36), activefrom DATE NOT NULL, "
                            + "activeto DATE NULL, rate DOUBLE, hours DOUBLE)",
                    // Platform table (V143, reshaped by V145/V267); V414 registers a settings tab in it.
                    "CREATE TABLE page_registry (id INT AUTO_INCREMENT PRIMARY KEY, "
                            + "page_key VARCHAR(50) NOT NULL UNIQUE, page_label VARCHAR(100) NOT NULL, "
                            + "is_visible BOOLEAN NOT NULL DEFAULT FALSE, react_route VARCHAR(100) NOT NULL, "
                            + "required_roles VARCHAR(255) NOT NULL DEFAULT 'USER', "
                            + "display_order INT NOT NULL DEFAULT 0, section VARCHAR(50), icon_name VARCHAR(50), "
                            + "is_external BOOLEAN NOT NULL DEFAULT FALSE, external_url VARCHAR(500) NULL)"
            }) {
                statement.execute(ddl);
            }
        }
    }
}
