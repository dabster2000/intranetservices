package dk.trustworks.intranet.competenceservice.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Raw-JDBC fixture and assertion helpers shared by the competence persistence tier
 * (spec §12.2).
 *
 * <p><strong>Why raw JDBC rather than Panache.</strong> These tests exist to prove what the
 * V495 triggers and unique indexes do, which means they have to issue writes the entity
 * layer deliberately cannot express — an UPDATE against an append-only table, a DELETE of an
 * attempt, a second ACTIVE row for the same (requirement, kind). They also have to keep
 * running after each refused write, and a {@code SIGNAL} arriving through Hibernate marks the
 * JTA transaction rollback-only, which would poison the very re-read that proves the write
 * did not take effect. Every statement here therefore runs on an autocommit connection: a
 * refused statement is rolled back on its own and the connection stays usable.
 *
 * <p><strong>Deliberate leftovers.</strong> {@code competence_attempt},
 * {@code competence_course_completion}, {@code competence_attempt_decision} and
 * {@code competence_settings_audit} refuse DELETE at the database level, so a test that
 * inserts into them cannot clean up after itself — that refusal is the thing under test.
 * Every fixture row is therefore keyed on a fresh {@link UUID} per test run so leftovers can
 * never collide with a later run, and requirements carry the {@link #COMP_ID_PREFIX} marker
 * so they are identifiable (and purgeable by a human with DDL rights, by dropping the
 * triggers first). Rows that <em>can</em> be removed are removed in {@code @AfterAll}.
 *
 * <p>This class carries no {@code Test} suffix on purpose: surefire's default includes are
 * {@code Test*}, {@code *Test}, {@code *Tests}, {@code *TestCase}, so it is never collected
 * as a test class.
 */
final class CompetenceFixtures {

    /** Marker on every fixture requirement, so leftovers are recognisable in the database. */
    static final String COMP_ID_PREFIX = "ZZ-IT-COMP-";

    /**
     * The smallest COURSE payload that survives {@code CompetenceContentValidator.validateCourse}:
     * one screen, a known role, a title and one non-empty block. It carries no
     * {@code [Udfyldes} marker, so {@code CompetencePlaceholderScanner} finds nothing and
     * publish does not need {@code acknowledgeUnresolved}.
     */
    static final String MINIMAL_COURSE_PAYLOAD = """
            {"screens":[{"role":"intro","title":"Introduktion",\
            "blocks":[{"type":"paragraph","text":"Testindhold uden markoerer."}]}]}""";

    /**
     * The smallest TEST payload that survives {@code CompetenceContentValidator.validateTest}:
     * one question with an id and text, and exactly two options with exactly one marked
     * correct. The two payload shapes are mutually exclusive — {@code TestPayload} rejects
     * unknown properties, so handing a course payload to a TEST version fails at parse time.
     */
    static final String MINIMAL_TEST_PAYLOAD = """
            {"questions":[{"id":"q1","text":"Hvad er den dokumenterede udviklingsmetode?",\
            "options":[{"id":"a","text":"Den rigtige","correct":true},\
            {"id":"b","text":"Den forkerte","correct":false}]}]}""";

    private CompetenceFixtures() {
    }

    /** The payload a version of this kind must carry to survive validation. */
    static String defaultPayloadFor(String kind) {
        return "TEST".equals(kind) ? MINIMAL_TEST_PAYLOAD : MINIMAL_COURSE_PAYLOAD;
    }

    static String newUuid() {
        return UUID.randomUUID().toString();
    }

    // -----------------------------------------------------------------------
    // statement plumbing
    // -----------------------------------------------------------------------

    /**
     * Runs a write on an autocommit connection and returns the affected row count.
     *
     * <p>Autocommit is asserted rather than assumed: if a JTA transaction were active the
     * connection would be enlisted and a refused statement would take the surrounding
     * transaction with it.
     */
    static int update(DataSource ds, String sql, Object... params) throws SQLException {
        try (Connection c = ds.getConnection()) {
            if (!c.getAutoCommit()) {
                c.setAutoCommit(true);
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        }
    }

    /**
     * Asserts the database refuses a write, and hands back the exception so the caller can
     * assert on <em>how</em> it was refused (SQLSTATE 45000 for a trigger {@code SIGNAL},
     * 23000 for a unique-index collision) rather than on message text alone.
     */
    static SQLException expectRefused(DataSource ds, String sql, Object... params) {
        try {
            int affected = update(ds, sql, params);
            throw new AssertionError(
                    "Expected the database to refuse this write, but it affected " + affected
                            + " row(s): " + sql);
        } catch (SQLException expected) {
            return expected;
        }
    }

    /**
     * The first non-null SQLSTATE in the throwable chain.
     *
     * <p>Walked rather than read off the top exception because the driver, the pool and
     * (when a write goes through the entity layer) Hibernate each wrap the original.
     */
    static String sqlStateOf(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /** Single row as column-name → value, or {@code null} when there is no row. */
    static Map<String, Object> row(DataSource ds, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = rows(ds, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static List<Map<String, Object>> rows(DataSource ds, String sql, Object... params) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        values.put(meta.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                    }
                    out.add(values);
                }
                return out;
            }
        }
    }

    static long count(DataSource ds, String sql, Object... params) throws SQLException {
        Map<String, Object> row = row(ds, sql, params);
        return row == null ? 0L : ((Number) row.values().iterator().next()).longValue();
    }

    /**
     * A {@code TINYINT(1)} / {@code SMALLINT} column as an int.
     *
     * <p>Tolerant of the driver's type choice on purpose: the MariaDB connector maps
     * {@code TINYINT(1)} to {@link Boolean} when {@code tinyInt1isBit} is on (its default) and
     * to a {@link Number} when it is off, and the flag is a connection-URL option that differs
     * between a developer's local URL and the deployed one. A test that hard-cast to one of
     * them would fail for a reason that has nothing to do with the schema under test.
     */
    static int asInt(Object value) {
        if (value == null) {
            throw new AssertionError("Expected a numeric column value, got NULL");
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue ? 1 : 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    /** {@code completed_at} and friends come back as {@link Timestamp}. */
    static LocalDateTime asDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        throw new IllegalArgumentException("Not a datetime: " + value.getClass());
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object param = params[i];
            if (param instanceof LocalDateTime localDateTime) {
                ps.setTimestamp(i + 1, Timestamp.valueOf(localDateTime));
            } else {
                ps.setObject(i + 1, param);
            }
        }
    }

    // -----------------------------------------------------------------------
    // fixture rows
    // -----------------------------------------------------------------------

    /**
     * A requirement, which every other competence row needs: {@code competence_attempt},
     * {@code competence_course_completion} and {@code competence_content_version} all carry
     * an FK onto it.
     *
     * <p>No user row is created anywhere in this tier and none is needed: {@code useruuid} is
     * a soft reference with no FK on all three tables (V495, following the V489 convention),
     * which is exactly what lets GDPR pseudonymisation rewrite it.
     */
    static String insertRequirement(DataSource ds, String label) throws SQLException {
        String uuid = newUuid();
        update(ds, """
                        INSERT INTO competence_requirement
                            (uuid, comp_id, kref, name, sort_order, active,
                             created_at, created_by, updated_at, modified_by)
                        VALUES (?, ?, ?, ?, 0, 1, NOW(6), 'quarkustest', NOW(6), 'quarkustest')""",
                uuid,
                COMP_ID_PREFIX + label + "-" + uuid.substring(0, 8),
                "ZZ-IT",
                "Fixture requirement " + label);
        return uuid;
    }

    static String insertContentVersion(DataSource ds, String requirementUuid, String kind,
                                       String versionLabel, String status) throws SQLException {
        return insertContentVersion(ds, requirementUuid, kind, versionLabel, status,
                defaultPayloadFor(kind), null, null);
    }

    /**
     * @param suppliedActiveKey value the application tries to write into {@code active_key};
     *                          the insert trigger overwrites it unconditionally, which is
     *                          itself asserted by {@code CompetenceUniqueActiveConstraintTest}
     */
    static String insertContentVersion(DataSource ds, String requirementUuid, String kind,
                                       String versionLabel, String status, String payloadJson,
                                       String suppliedActiveKey, String suppliedDraftKey)
            throws SQLException {
        String uuid = newUuid();
        update(ds, """
                        INSERT INTO competence_content_version
                            (uuid, requirement_uuid, content_kind, version_label, status,
                             payload_json, forced_retake, created_at, created_by,
                             updated_at, modified_by, active_key, draft_key)
                        VALUES (?, ?, ?, ?, ?, ?, 1, NOW(6), 'quarkustest', NOW(6), 'quarkustest', ?, ?)""",
                uuid, requirementUuid, kind, versionLabel, status, payloadJson,
                suppliedActiveKey, suppliedDraftKey);
        return uuid;
    }

    static String insertCompletion(DataSource ds, String useruuid, String requirementUuid,
                                   String contentVersionUuid, String versionLabel,
                                   LocalDateTime completedAt) throws SQLException {
        String uuid = newUuid();
        update(ds, """
                        INSERT INTO competence_course_completion
                            (uuid, useruuid, requirement_uuid, content_version_uuid,
                             version_label, completed_at)
                        VALUES (?, ?, ?, ?, ?, ?)""",
                uuid, useruuid, requirementUuid, contentVersionUuid, versionLabel, completedAt);
        return uuid;
    }

    /**
     * An attempt row.
     *
     * @param submittedAt {@code null} leaves the attempt open — the only state from which the
     *                    trigger permits the scoring write or the reaper's abandon flag
     */
    static String insertAttempt(DataSource ds, String useruuid, String requirementUuid,
                                String contentVersionUuid, LocalDateTime submittedAt)
            throws SQLException {
        String uuid = newUuid();
        boolean submitted = submittedAt != null;
        update(ds, """
                        INSERT INTO competence_attempt
                            (uuid, useruuid, requirement_uuid, content_version_uuid, version_label,
                             kref, threshold_snapshot, option_order_json, started_at, submitted_at,
                             correct_count, question_count, score, passed, abandoned)
                        VALUES (?, ?, ?, ?, 'v1', 'ZZ-IT', 0.800, '[]', ?, ?, ?, 10, ?, ?, 0)""",
                uuid, useruuid, requirementUuid, contentVersionUuid,
                LocalDateTime.now().minusMinutes(10),
                submittedAt,
                submitted ? Integer.valueOf(9) : null,
                submitted ? new java.math.BigDecimal("0.9000") : null,
                submitted ? Integer.valueOf(1) : null);
        return uuid;
    }

    static String insertDecision(DataSource ds, String attemptUuid, String actorUuid) throws SQLException {
        String uuid = newUuid();
        update(ds, """
                        INSERT INTO competence_attempt_decision
                            (uuid, attempt_uuid, decision, actor_uuid, decided_at, note)
                        VALUES (?, ?, 'APPROVED', ?, ?, 'fixture')""",
                uuid, attemptUuid, actorUuid, LocalDateTime.now());
        return uuid;
    }

    static String insertSettingsAudit(DataSource ds, String settingKey) throws SQLException {
        String uuid = newUuid();
        update(ds, """
                        INSERT INTO competence_settings_audit
                            (uuid, setting_key, old_value, new_value, changed_by, changed_at)
                        VALUES (?, ?, '0.8', '0.9', 'quarkustest', ?)""",
                uuid, settingKey, LocalDateTime.now());
        return uuid;
    }

    // -----------------------------------------------------------------------
    // cleanup
    // -----------------------------------------------------------------------

    /**
     * Best-effort removal. Content versions and requirements are removable; anything the
     * append-only triggers protect, or anything still referenced by such a row, is not — so a
     * failure here is expected and is not a test failure.
     */
    static void quietlyDelete(DataSource ds, String sql, Object... params) {
        try {
            update(ds, sql, params);
        } catch (SQLException | RuntimeException ignored) {
            // Left behind on purpose: see the class javadoc.
        }
    }

    static void quietlyDropFixture(DataSource ds, String requirementUuid) {
        if (requirementUuid == null) {
            return;
        }
        quietlyDelete(ds, "DELETE FROM competence_content_version WHERE requirement_uuid = ?",
                requirementUuid);
        quietlyDelete(ds, "DELETE FROM competence_requirement WHERE uuid = ?", requirementUuid);
    }
}
