package dk.trustworks.intranet.aggregates.practices.services;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in disposable MariaDB harness proving the defect-9 supersession lifecycle and the defect-10
 * dependency-manifest lifecycle against real constraints, procedures and multiple connections. It only
 * ever targets the disposable {@code practices_coherence} schema and drops/rebuilds it per test.
 */
@EnabledIfEnvironmentVariable(named = "PRACTICES_COHERENCE_JDBC_URL", matches = ".+")
class PracticeCostRequestLifecycleMariaDbIT {

    private static String url() { return System.getenv("PRACTICES_COHERENCE_JDBC_URL"); }
    private static String user() { return System.getenv().getOrDefault("PRACTICES_COHERENCE_JDBC_USER", ""); }
    private static String password() {
        return System.getenv().getOrDefault("PRACTICES_COHERENCE_JDBC_PASSWORD", "");
    }

    @Test
    void enqueuingANewerCoveringInputRetiresDominatedPendingToSupersededWithSuccessor() throws Exception {
        bootstrap("413");
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CALL sp_enqueue_practice_cost_basis_refresh('COST_GL_INPUT','OPERATOR',NULL,NULL)");
            long first = latestRequestId(s);
            s.execute("CALL sp_enqueue_practice_cost_basis_refresh('PRACTICE_BASIS_INPUT','OPERATOR',NULL,NULL)");
            long second = latestRequestId(s);
            assertTrue(second > first, "second request must be newer");

            assertEquals("SUPERSEDED", status(s, first));
            assertEquals(second, supersededBy(s, first));
            assertEquals("PENDING", status(s, second));
            assertNull(supersededByOrNull(s, second), "the newest request is never superseded");
            // Invariant: exactly one PENDING row, and it is the newest — older work is claimable no more.
            assertEquals(1, count(s, "SELECT COUNT(*) FROM practice_cost_basis_refresh_request WHERE status='PENDING'"));
            assertEquals(0, count(s, "SELECT COUNT(*) FROM practice_cost_basis_refresh_request "
                    + "WHERE superseded_by_request_id = request_id"));
        }
    }

    @Test
    void dependencyManifestEscalationAdvancesMonotonicVersionEnqueuesAndIsIdempotentPerFingerprint()
            throws Exception {
        bootstrap("413");
        try (Connection c = connect(); Statement s = c.createStatement()) {
            long before = manifestInputVersion(s);
            s.execute("CALL sp_advance_practice_dependency_manifest_input(NULL,NULL,'" + "a".repeat(64) + "')");
            long afterFirst = manifestInputVersion(s);
            assertEquals(before + 1, afterFirst, "an accepted manifest change advances the monotonic version");
            long escalated = latestRequestId(s);
            assertEquals("DEPENDENCY_MANIFEST_INPUT", cause(s, escalated));
            assertEquals("a".repeat(64), dependencyFingerprint(s, escalated));

            // Identical repeat for the same still-PENDING fingerprint is idempotent: no new version/request.
            s.execute("CALL sp_advance_practice_dependency_manifest_input(NULL,NULL,'" + "a".repeat(64) + "')");
            assertEquals(afterFirst, manifestInputVersion(s));
            assertEquals(escalated, latestRequestId(s));
            assertEquals(1, count(s, "SELECT COUNT(*) FROM practice_cost_basis_refresh_request "
                    + "WHERE cause='DEPENDENCY_MANIFEST_INPUT'"));

            // A fingerprint that reverts to a historically seen value still gets a new monotonic version.
            s.execute("CALL sp_advance_practice_dependency_manifest_input(NULL,NULL,'" + "b".repeat(64) + "')");
            long afterSecond = manifestInputVersion(s);
            assertEquals(afterFirst + 1, afterSecond);
            s.execute("CALL sp_advance_practice_dependency_manifest_input(NULL,NULL,'" + "a".repeat(64) + "')");
            assertEquals(afterSecond + 1, manifestInputVersion(s),
                    "a reverted fingerprint is still a new accepted change");
        }
    }

    @Test
    void certifiedDependencyFingerprintMakesTheActivationEqualityPredicateSatisfiable() throws Exception {
        bootstrap("413");
        String fingerprint = "c".repeat(64);
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO practice_basis_generation (generation_id,status,coverage_start_date,"
                    + "coverage_end_date,fallback_policy_version,consultant_type_policy_version,"
                    + "full_refresh_version,incremental_refresh_version,practice_basis_input_source_version,"
                    + "source_fingerprint,capacity_source_fingerprint,dependency_manifest_fingerprint,created_at,"
                    + "published_at) VALUES ('gen-1','READY','2021-07-01','2026-06-30','fp-v1','type-v1',0,0,0,"
                    + "REPEAT('9',64),REPEAT('8',64),'" + fingerprint + "',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            s.execute("INSERT INTO practice_cost_basis_refresh_request (request_key,cause,trigger_origin,"
                    + "cause_input_version,expected_full_refresh_version,expected_incremental_refresh_version,"
                    + "expected_practice_basis_input_version,expected_finance_gl_version,"
                    + "expected_account_classification_version,input_vector_fingerprint,dependency_fingerprint,"
                    + "status,resulting_basis_generation_id) VALUES (REPEAT('1',64),'FULL_BI','PROCEDURE',0,0,0,0,"
                    + "0,0,REPEAT('a',64),'" + fingerprint + "','READY','gen-1')");

            // The certified fingerprint on the request equals the basis manifest fingerprint -> the equality
            // predicate that blocks controlled activation while NULL now passes for exactly this pair.
            assertEquals(1, count(s, "SELECT COUNT(*) FROM practice_cost_basis_refresh_request r "
                    + "JOIN practice_basis_generation basis ON basis.generation_id=r.resulting_basis_generation_id "
                    + "WHERE r.dependency_fingerprint = basis.dependency_manifest_fingerprint"));
            // A NULL or mismatched fingerprint can never satisfy SQL equality.
            s.execute("UPDATE practice_cost_basis_refresh_request SET dependency_fingerprint=NULL");
            assertEquals(0, count(s, "SELECT COUNT(*) FROM practice_cost_basis_refresh_request r "
                    + "JOIN practice_basis_generation basis ON basis.generation_id=r.resulting_basis_generation_id "
                    + "WHERE r.dependency_fingerprint = basis.dependency_manifest_fingerprint"));
        }
    }

    @Test
    void multipleConnectionsEnqueuingConvergeOnASingleClaimablePendingRequest() throws Exception {
        bootstrap("413");
        try (Connection a = connect(); Connection b = connect();
             Statement sa = a.createStatement(); Statement sb = b.createStatement()) {
            sa.execute("CALL sp_enqueue_practice_cost_basis_refresh('COST_GL_INPUT','OPERATOR',NULL,NULL)");
            long first = latestRequestId(sa);
            sb.execute("CALL sp_enqueue_practice_cost_basis_refresh('PRACTICE_BASIS_INPUT','OPERATOR',NULL,NULL)");
            long second = latestRequestId(sb);
        }
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertEquals(1, count(s, "SELECT COUNT(*) FROM practice_cost_basis_refresh_request WHERE status='PENDING'"));
            // Every non-newest PENDING was retired, and no row ever links to itself.
            assertEquals(0, count(s, "SELECT COUNT(*) FROM practice_cost_basis_refresh_request "
                    + "WHERE superseded_by_request_id = request_id"));
            assertEquals(0, count(s, "SELECT COUNT(*) FROM practice_cost_basis_refresh_request r "
                    + "WHERE r.status='PENDING' AND EXISTS (SELECT 1 FROM practice_cost_basis_refresh_request n "
                    + "WHERE n.request_id > r.request_id)"));
        }
    }

    @Test
    void v413StaleRepairSupersedesDominatedPendingRowsFromTheV412PredecessorState() throws Exception {
        dropAllTables();
        createV410PredecessorFixture();
        Flyway throughV412 = Flyway.configure()
                .dataSource(url(), user(), password())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("410").target("412").load();
        throughV412.migrate();

        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (int i = 1; i <= 3; i++) {
                s.execute("INSERT INTO practice_cost_basis_refresh_request (request_key,cause,trigger_origin,"
                        + "cause_input_version,expected_full_refresh_version,expected_incremental_refresh_version,"
                        + "expected_practice_basis_input_version,expected_finance_gl_version,"
                        + "expected_account_classification_version,input_vector_fingerprint,status) VALUES ("
                        + "REPEAT('" + i + "',64),'COST_GL_INPUT','PROCEDURE'," + i + ",0,0,0,0,0,REPEAT('a',64),'PENDING')");
            }
        }

        Flyway.configure().dataSource(url(), user(), password())
                .locations("classpath:db/migration").load().migrate();

        try (Connection c = connect(); Statement s = c.createStatement()) {
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = s.executeQuery(
                    "SELECT request_id FROM practice_cost_basis_refresh_request ORDER BY request_id")) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            assertEquals(3, ids.size());
            long newest = ids.get(2);
            assertEquals("SUPERSEDED", status(s, ids.get(0)));
            assertEquals("SUPERSEDED", status(s, ids.get(1)));
            assertEquals(newest, supersededBy(s, ids.get(0)));
            assertEquals(newest, supersededBy(s, ids.get(1)));
            assertEquals("PENDING", status(s, newest));
            assertNull(supersededByOrNull(s, newest));

            // No-op / idempotent: re-running the exact repair statement changes nothing more.
            int repaired = s.executeUpdate("""
                    UPDATE practice_cost_basis_refresh_request r
                    JOIN (SELECT MAX(request_id) AS newest FROM practice_cost_basis_refresh_request) m
                       SET r.status='SUPERSEDED', r.superseded_by_request_id=m.newest,
                           r.optimistic_version=r.optimistic_version+1
                     WHERE r.status='PENDING' AND r.request_id < m.newest
                    """);
            assertEquals(0, repaired, "the repair is a no-op once the invariant holds");
        }

        // Flyway validation stays green after a restart against the same history.
        Flyway.configure().dataSource(url(), user(), password())
                .locations("classpath:db/migration").load().validate();
    }

    @Test
    void serviceMonthDeliveryBranchEmitsOutOfWindowServiceMonthsForDocumentAndCreditSource()
            throws Exception {
        bootstrap("413");
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE invoices (uuid VARCHAR(40) PRIMARY KEY, status VARCHAR(20), "
                    + "type VARCHAR(20), invoicedate DATE, creditnote_for_uuid VARCHAR(40) NULL, "
                    + "year INT NULL, month INT NULL)");
            // Arrears INVOICE: recognized 2026-03 but serviced 2023-02 (before the recognition window).
            s.execute("INSERT INTO invoices VALUES ('inv-arrears','CREATED','INVOICE','2026-03-15',NULL,2023,2)");
            s.execute("INSERT INTO invoiceitems (uuid) VALUES ('item-arrears')");
            s.execute("INSERT INTO invoices VALUES ('src-old','CREATED','INVOICE','2020-05-10',NULL,2020,5)");
            s.execute("INSERT INTO invoices VALUES ('cn-1','CREATED','CREDIT_NOTE','2026-04-10','src-old',2020,5)");
            s.execute("INSERT INTO invoiceitems (uuid) VALUES ('item-cn')");
            // The scan joins invoiceitems by invoiceuuid; add that column if the migrated shape lacks it.
            s.execute("ALTER TABLE invoiceitems ADD COLUMN IF NOT EXISTS invoiceuuid VARCHAR(40) NULL");
            s.execute("UPDATE invoiceitems SET invoiceuuid='inv-arrears' WHERE uuid='item-arrears'");
            s.execute("UPDATE invoiceitems SET invoiceuuid='cn-1' WHERE uuid='item-cn'");

            String scan = dk.trustworks.intranet.aggregates.practices.services
                    .PracticeRevenueDependencyManifestProvider.DOCUMENT_SCAN_SQL
                    .replace(":recognizedStart", "'2026-01-01'")
                    .replace(":recognizedEndExclusive", "'2027-01-01'");
            boolean documentServiceMonth = false;
            boolean creditSourceServiceMonth = false;
            try (ResultSet rs = s.executeQuery(scan)) {
                while (rs.next()) {
                    String kind = rs.getString("dependency_kind");
                    if (!"SERVICE_MONTH_DELIVERY".equals(kind)) continue;
                    String recognized = rs.getString("recognized_document_uuid");
                    String source = rs.getString("source_document_uuid");
                    String from = rs.getString("required_start_date");
                    String to = rs.getString("required_end_date");
                    if ("inv-arrears".equals(recognized) && "inv-arrears".equals(source)
                            && "2023-02-01".equals(from) && "2023-02-28".equals(to)) documentServiceMonth = true;
                    if ("cn-1".equals(recognized) && "src-old".equals(source)
                            && "2020-05-01".equals(from) && "2020-05-31".equals(to)) creditSourceServiceMonth = true;
                }
            }
            assertTrue(documentServiceMonth, "document service-month delivery window must be emitted");
            assertTrue(creditSourceServiceMonth, "one-hop credit-source service-month window must be emitted");
        }
    }

    @Test
    void fullBiRequestCapturingTheLiveIncrementalVersionIsClaimEligible() throws Exception {
        bootstrap("413");
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("UPDATE bi_refresh_watermark SET full_refresh_version=5, incremental_refresh_version=3 "
                    + "WHERE pipeline_name='FACT_USER_DAY'");
            long basis = count(s, "SELECT source_version FROM practice_revenue_source_watermark "
                    + "WHERE source_name='PRACTICE_BASIS_INPUT'");
            long finance = count(s, "SELECT source_version FROM practice_revenue_source_watermark "
                    + "WHERE source_name='FINANCE_GL'");
            long classification = count(s, "SELECT source_version FROM practice_revenue_source_watermark "
                    + "WHERE source_name='ACCOUNT_CLASSIFICATION'");
            s.execute("UPDATE practice_contribution_publication_control SET refresh_enabled=TRUE WHERE control_id=1");
            s.execute("INSERT INTO practice_cost_basis_refresh_request (request_key,cause,trigger_origin,"
                    + "cause_input_version,expected_full_refresh_version,expected_incremental_refresh_version,"
                    + "expected_practice_basis_input_version,expected_finance_gl_version,"
                    + "expected_account_classification_version,input_vector_fingerprint,status) VALUES ("
                    + "REPEAT('7',64),'FULL_BI','PROCEDURE',5,5,3," + basis + "," + finance + "," + classification
                    + ",REPEAT('a',64),'PENDING')");
            long id = count(s, "SELECT MAX(request_id) FROM practice_cost_basis_refresh_request");
            s.execute("UPDATE practice_operating_cost_publication SET latest_cost_basis_request_id=" + id
                    + ", latest_cost_basis_request_vector=REPEAT('a',64) WHERE publication_id=1");

            // Captures the LIVE incremental version -> satisfies the BatchScheduler eligibility clause verbatim.
            assertEquals(1, count(s, ELIGIBILITY_SQL));
            // The old hardcoded-0 behaviour: once any incremental ran, the FULL_BI row is unclaimable.
            s.execute("UPDATE practice_cost_basis_refresh_request SET expected_incremental_refresh_version=0 "
                    + "WHERE request_id=" + id);
            assertEquals(0, count(s, ELIGIBILITY_SQL));
        }
    }

    private static final String ELIGIBILITY_SQL = """
            SELECT COUNT(*)
            FROM practice_cost_basis_refresh_request r
            JOIN practice_contribution_publication_control c ON c.control_id=1
            JOIN practice_operating_cost_publication p ON p.publication_id=1
            JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
            JOIN practice_revenue_source_watermark pb ON pb.source_name='PRACTICE_BASIS_INPUT'
            JOIN practice_revenue_source_watermark fg ON fg.source_name='FINANCE_GL'
            JOIN practice_revenue_source_watermark ac ON ac.source_name='ACCOUNT_CLASSIFICATION'
            WHERE r.status='PENDING' AND c.refresh_enabled=TRUE
              AND c.revenue_recovery_owner_token IS NULL
              AND b.refresh_state='READY' AND b.active_refresh_token IS NULL
              AND b.certified_complete_through_date IS NOT NULL
              AND r.request_id=p.latest_cost_basis_request_id
              AND r.input_vector_fingerprint=p.latest_cost_basis_request_vector
              AND r.expected_full_refresh_version=b.full_refresh_version
              AND r.expected_incremental_refresh_version=b.incremental_refresh_version
              AND r.expected_practice_basis_input_version=pb.source_version
              AND r.expected_finance_gl_version=fg.source_version
              AND r.expected_account_classification_version=ac.source_version
            """;

    // ---- helpers ------------------------------------------------------------------------------------

    private void bootstrap(String target) throws SQLException {
        dropAllTables();
        createV410PredecessorFixture();
        Flyway flyway = Flyway.configure()
                .dataSource(url(), user(), password())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("410").target(target).load();
        flyway.migrate();
        flyway.validate();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url(), user(), password());
    }

    private void dropAllTables() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            List<String> tables = collect(s,
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()");
            List<String> triggers = collect(s,
                    "SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema = DATABASE()");
            List<String> routines = collect(s,
                    "SELECT routine_name FROM information_schema.routines WHERE routine_schema = DATABASE()");
            s.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String trigger : triggers) s.execute("DROP TRIGGER IF EXISTS `" + trigger + "`");
            for (String routine : routines) s.execute("DROP PROCEDURE IF EXISTS `" + routine + "`");
            for (String table : tables) s.execute("DROP TABLE IF EXISTS `" + table + "`");
            s.execute("SET FOREIGN_KEY_CHECKS=1");
            // Flyway schema history is dropped with the tables; a fresh baseline follows.
            s.execute("DROP TABLE IF EXISTS `flyway_schema_history`");
        }
    }

    private List<String> collect(Statement s, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) values.add(rs.getString(1));
        }
        return values;
    }

    private long latestRequestId(Statement s) throws SQLException {
        return count(s, "SELECT latest_cost_basis_request_id FROM practice_operating_cost_publication "
                + "WHERE publication_id=1");
    }
    private long manifestInputVersion(Statement s) throws SQLException {
        return count(s, "SELECT dependency_manifest_input_version FROM bi_refresh_watermark "
                + "WHERE pipeline_name='FACT_USER_DAY'");
    }
    private String status(Statement s, long id) throws SQLException {
        return text(s, "SELECT status FROM practice_cost_basis_refresh_request WHERE request_id=" + id);
    }
    private String cause(Statement s, long id) throws SQLException {
        return text(s, "SELECT cause FROM practice_cost_basis_refresh_request WHERE request_id=" + id);
    }
    private String dependencyFingerprint(Statement s, long id) throws SQLException {
        return text(s, "SELECT dependency_fingerprint FROM practice_cost_basis_refresh_request WHERE request_id=" + id);
    }
    private long supersededBy(Statement s, long id) throws SQLException {
        return count(s, "SELECT superseded_by_request_id FROM practice_cost_basis_refresh_request WHERE request_id=" + id);
    }
    private Long supersededByOrNull(Statement s, long id) throws SQLException {
        try (ResultSet rs = s.executeQuery(
                "SELECT superseded_by_request_id FROM practice_cost_basis_refresh_request WHERE request_id=" + id)) {
            rs.next();
            long value = rs.getLong(1);
            return rs.wasNull() ? null : value;
        }
    }
    private long count(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }
    private String text(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }

    private void createV410PredecessorFixture() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
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
                            + "activeto DATE NULL, rate DOUBLE, hours DOUBLE)"
            }) {
                statement.execute(ddl);
            }
        }
    }
}
