package dk.trustworks.intranet.competenceservice.persistence;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.asDateTime;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.expectRefused;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.row;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.sqlStateOf;
import static dk.trustworks.intranet.competenceservice.persistence.CompetenceFixtures.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The V495 immutability triggers, exercised by issuing the writes they exist to refuse
 * (spec §4.4, §4.5, §4.6, §10.6, §12.2).
 *
 * <p><strong>Why this tier and not the DB-free one.</strong> Nothing in the Java code enforces
 * any of this. The control is "an auditor can issue an UPDATE against the evidence and the
 * database says no", and the only way to test that claim is to issue the UPDATE. A unit test
 * can at best assert that the service does not <em>attempt</em> an edit, which is a different
 * and much weaker statement — it says nothing about a DBA, a migration, or a future endpoint.
 *
 * <p><strong>What each assertion is worth.</strong> Every refusal is asserted three ways: the
 * statement throws, the throwable chain carries SQLSTATE {@code 45000} (a trigger
 * {@code SIGNAL}, not an FK, a column width or a parse error that would also throw), and the
 * row is re-read to show the write did not land. The third check is the one that would catch a
 * trigger that raised a warning instead of an error.
 *
 * <p><strong>Four permitted writes, three of them on {@code competence_attempt}.</strong> The
 * attempt trigger is not a blanket refusal — it whitelists the scoring write, the reaper's
 * abandon flag and the GDPR {@code erased:} pseudonymisation (§19.5). Testing only the
 * refusals would leave a trigger that refuses everything looking perfectly healthy while the
 * module could not score a test.
 *
 * <p>Rows inserted here cannot be deleted afterwards — that is the point of the tables — so
 * every uuid is generated per run. See {@link CompetenceFixtures}.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompetenceImmutabilityTest {

    /** A trigger {@code SIGNAL SQLSTATE '45000'}, as distinct from any other write failure. */
    private static final String TRIGGER_SIGNAL = "45000";

    @Inject
    DataSource ds;

    private String requirementUuid;
    private String contentVersionUuid;

    @BeforeAll
    void seedFixture() throws SQLException {
        requirementUuid = CompetenceFixtures.insertRequirement(ds, "immutability");
        contentVersionUuid = CompetenceFixtures.insertContentVersion(
                ds, requirementUuid, "TEST", "v1", "ACTIVE");
    }

    @AfterAll
    void dropWhatCanBeDropped() {
        // The attempts, completions, decisions and audit rows below stay: DELETE is refused,
        // which keeps their content version and requirement undeletable too. Best effort only.
        CompetenceFixtures.quietlyDropFixture(ds, requirementUuid);
    }

    // =======================================================================
    // competence_attempt — the three permitted transitions
    // =======================================================================

    @Test
    @DisplayName("competence_attempt: the scoring write is permitted (submitted_at NULL -> NOT NULL)")
    void scoringWriteIsPermitted() throws SQLException {
        String attemptUuid = openAttempt();
        LocalDateTime submittedAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        int affected = update(ds, """
                        UPDATE competence_attempt
                           SET submitted_at = ?, correct_count = 9, score = 0.9000, passed = 1
                         WHERE uuid = ?""",
                submittedAt, attemptUuid);

        assertEquals(1, affected, "The scoring write is the whole point of the table being writable at all");
        Map<String, Object> after = attempt(attemptUuid);
        assertEquals(submittedAt, asDateTime(after.get("submitted_at")));
        assertEquals(9, CompetenceFixtures.asInt(after.get("correct_count")));
        assertEquals(0, new BigDecimal("0.9000").compareTo((BigDecimal) after.get("score")));
        assertEquals(1, CompetenceFixtures.asInt(after.get("passed")));
    }

    @Test
    @DisplayName("competence_attempt: the reaper's abandoned 0 -> 1 on a still-open row is permitted")
    void reaperAbandonFlagIsPermitted() throws SQLException {
        String attemptUuid = openAttempt();

        int affected = update(ds,
                "UPDATE competence_attempt SET abandoned = 1 WHERE uuid = ?", attemptUuid);

        assertEquals(1, affected);
        Map<String, Object> after = attempt(attemptUuid);
        assertEquals(1, CompetenceFixtures.asInt(after.get("abandoned")));
        assertNull(after.get("submitted_at"), "The reaper closes an attempt without scoring it");
        assertNull(after.get("score"));
    }

    @Test
    @DisplayName("competence_attempt: an 'erased:' pseudonymisation is permitted (§19.5 / §10.9)")
    void gdprPseudonymisationIsPermitted() throws SQLException {
        String attemptUuid = submittedAttempt();
        Map<String, Object> before = attempt(attemptUuid);
        String erased = erasedPseudonym();

        int affected = update(ds,
                "UPDATE competence_attempt SET useruuid = ? WHERE uuid = ?", erased, attemptUuid);

        assertEquals(1, affected,
                "Retention would be unimplementable against the immutability rule without this branch");
        Map<String, Object> after = attempt(attemptUuid);
        assertEquals(erased, after.get("useruuid"));
        // Everything else must be untouched — the row stops identifying a person and stays
        // usable as aggregate compliance history.
        assertEquals(before.get("requirement_uuid"), after.get("requirement_uuid"));
        assertEquals(before.get("content_version_uuid"), after.get("content_version_uuid"));
        assertEquals(asDateTime(before.get("submitted_at")), asDateTime(after.get("submitted_at")));
        assertEquals(0, ((BigDecimal) before.get("score")).compareTo((BigDecimal) after.get("score")));
    }

    // =======================================================================
    // competence_attempt — everything else
    // =======================================================================

    @Test
    @DisplayName("competence_attempt: a second scoring write is refused")
    void secondScoringWriteIsRefused() throws SQLException {
        String attemptUuid = submittedAttempt();
        Map<String, Object> before = attempt(attemptUuid);

        SQLException refusal = expectRefused(ds, """
                        UPDATE competence_attempt
                           SET submitted_at = ?, correct_count = 4, score = 0.4000, passed = 0
                         WHERE uuid = ?""",
                LocalDateTime.now(), attemptUuid);

        assertSignalled(refusal);
        assertUnchanged(before, attempt(attemptUuid));
    }

    @Test
    @DisplayName("competence_attempt: rewriting the frozen threshold snapshot is refused")
    void thresholdRewriteIsRefused() throws SQLException {
        String attemptUuid = openAttempt();
        Map<String, Object> before = attempt(attemptUuid);

        // The threshold is frozen at start precisely so raising the pass mark cannot
        // retroactively fail somebody who already passed (§5.7).
        SQLException refusal = expectRefused(ds,
                "UPDATE competence_attempt SET threshold_snapshot = 0.500 WHERE uuid = ?", attemptUuid);

        assertSignalled(refusal);
        Map<String, Object> after = attempt(attemptUuid);
        assertEquals(0, new BigDecimal("0.800").compareTo((BigDecimal) after.get("threshold_snapshot")));
        assertUnchanged(before, after);
    }

    @Test
    @DisplayName("competence_attempt: an arbitrary useruuid swap is refused — only 'erased:' passes")
    void arbitraryUseruuidSwapIsRefused() throws SQLException {
        String attemptUuid = openAttempt();
        Map<String, Object> before = attempt(attemptUuid);

        SQLException refusal = expectRefused(ds,
                "UPDATE competence_attempt SET useruuid = ? WHERE uuid = ?",
                UUID.randomUUID().toString(), attemptUuid);

        assertSignalled(refusal);
        assertEquals(before.get("useruuid"), attempt(attemptUuid).get("useruuid"),
                "The GDPR escape hatch is auditable because it is not a general useruuid rewrite");
    }

    @Test
    @DisplayName("competence_attempt: changing the score after submission is refused")
    void scoreChangeAfterSubmissionIsRefused() throws SQLException {
        String attemptUuid = submittedAttempt();
        Map<String, Object> before = attempt(attemptUuid);

        SQLException refusal = expectRefused(ds,
                "UPDATE competence_attempt SET score = 1.0000, correct_count = 10 WHERE uuid = ?",
                attemptUuid);

        assertSignalled(refusal);
        Map<String, Object> after = attempt(attemptUuid);
        assertEquals(0, new BigDecimal("0.9000").compareTo((BigDecimal) after.get("score")));
        assertUnchanged(before, after);
    }

    @Test
    @DisplayName("competence_attempt: the abandon flag is refused once the attempt is submitted")
    void abandonFlagAfterSubmissionIsRefused() throws SQLException {
        String attemptUuid = submittedAttempt();

        // Branch (b) is gated on OLD.submitted_at IS NULL: a scored attempt is evidence and
        // cannot be reclassified as never-finished.
        SQLException refusal = expectRefused(ds,
                "UPDATE competence_attempt SET abandoned = 1 WHERE uuid = ?", attemptUuid);

        assertSignalled(refusal);
        assertEquals(0, CompetenceFixtures.asInt(attempt(attemptUuid).get("abandoned")));
    }

    @Test
    @DisplayName("competence_attempt: DELETE is refused unconditionally")
    void attemptDeleteIsRefused() throws SQLException {
        String attemptUuid = openAttempt();

        SQLException refusal =
                expectRefused(ds, "DELETE FROM competence_attempt WHERE uuid = ?", attemptUuid);

        assertSignalled(refusal);
        assertNotNull(attempt(attemptUuid), "GDPR erasure pseudonymises; it never deletes (§10.9)");
    }

    // =======================================================================
    // The three plainly append-only tables
    // =======================================================================

    @Test
    @DisplayName("competence_course_completion: UPDATE and DELETE are both refused")
    void courseCompletionIsAppendOnly() throws SQLException {
        LocalDateTime completedAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusDays(3);
        String completionUuid = CompetenceFixtures.insertCompletion(
                ds, UUID.randomUUID().toString(), requirementUuid, contentVersionUuid, "v1", completedAt);

        SQLException onUpdate = expectRefused(ds,
                "UPDATE competence_course_completion SET completed_at = ? WHERE uuid = ?",
                LocalDateTime.now(), completionUuid);
        assertSignalled(onUpdate);

        SQLException onDelete = expectRefused(ds,
                "DELETE FROM competence_course_completion WHERE uuid = ?", completionUuid);
        assertSignalled(onDelete);

        Map<String, Object> after = row(ds,
                "SELECT * FROM competence_course_completion WHERE uuid = ?", completionUuid);
        assertNotNull(after);
        // Re-stamping completed_at is what force-retake-off carry-forward would have done if
        // the table allowed it; it appends instead, which is why the clock cannot be reset.
        assertEquals(completedAt, asDateTime(after.get("completed_at")));
    }

    @Test
    @DisplayName("competence_attempt_decision: UPDATE and DELETE are both refused")
    void attemptDecisionIsAppendOnly() throws SQLException {
        String attemptUuid = submittedAttempt();
        String decisionUuid = CompetenceFixtures.insertDecision(ds, attemptUuid, UUID.randomUUID().toString());

        // A wrong approval is corrected by appending a REVOKED row, never by editing.
        SQLException onUpdate = expectRefused(ds,
                "UPDATE competence_attempt_decision SET decision = 'REVOKED' WHERE uuid = ?", decisionUuid);
        assertSignalled(onUpdate);

        SQLException onDelete = expectRefused(ds,
                "DELETE FROM competence_attempt_decision WHERE uuid = ?", decisionUuid);
        assertSignalled(onDelete);

        Map<String, Object> after = row(ds,
                "SELECT * FROM competence_attempt_decision WHERE uuid = ?", decisionUuid);
        assertNotNull(after);
        assertEquals("APPROVED", String.valueOf(after.get("decision")));
    }

    @Test
    @DisplayName("competence_settings_audit: UPDATE and DELETE are both refused")
    void settingsAuditIsAppendOnly() throws SQLException {
        String settingKey = "competence.pass-threshold.zz-it-" + UUID.randomUUID();
        String auditUuid = CompetenceFixtures.insertSettingsAudit(ds, settingKey);

        SQLException onUpdate = expectRefused(ds,
                "UPDATE competence_settings_audit SET new_value = '0.5' WHERE uuid = ?", auditUuid);
        assertSignalled(onUpdate);

        SQLException onDelete = expectRefused(ds,
                "DELETE FROM competence_settings_audit WHERE uuid = ?", auditUuid);
        assertSignalled(onDelete);

        Map<String, Object> after = row(ds,
                "SELECT * FROM competence_settings_audit WHERE uuid = ?", auditUuid);
        assertNotNull(after);
        assertEquals("0.9", String.valueOf(after.get("new_value")));
    }

    // =======================================================================
    // helpers
    // =======================================================================

    private String openAttempt() throws SQLException {
        return CompetenceFixtures.insertAttempt(
                ds, UUID.randomUUID().toString(), requirementUuid, contentVersionUuid, null);
    }

    private String submittedAttempt() throws SQLException {
        return CompetenceFixtures.insertAttempt(
                ds, UUID.randomUUID().toString(), requirementUuid, contentVersionUuid,
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusMinutes(5));
    }

    private Map<String, Object> attempt(String attemptUuid) throws SQLException {
        return row(ds, "SELECT * FROM competence_attempt WHERE uuid = ?", attemptUuid);
    }

    /** {@code erased:} + 29 hex chars, so the pseudonym fits {@code useruuid CHAR(36)}. */
    private String erasedPseudonym() {
        return ("erased:" + UUID.randomUUID().toString().replace("-", "")).substring(0, 36);
    }

    /**
     * SQLSTATE 45000 is what {@code SIGNAL} raises. Asserting it distinguishes the trigger
     * refusing the write from an FK violation, a truncated column or a driver error, any of
     * which would otherwise let a broken trigger pass this test.
     */
    private void assertSignalled(SQLException refusal) {
        assertEquals(TRIGGER_SIGNAL, sqlStateOf(refusal),
                "Expected a trigger SIGNAL, got: " + refusal.getMessage());
        assertTrue(refusal.getMessage() != null && !refusal.getMessage().isBlank(),
                "The SIGNAL carries the MESSAGE_TEXT that tells a DBA why the write was refused");
    }

    /** Every column of the attempt is identical, which is what "immutable" has to mean. */
    private void assertUnchanged(Map<String, Object> before, Map<String, Object> after) {
        assertNotNull(after, "The row must still exist");
        for (Map.Entry<String, Object> entry : before.entrySet()) {
            Object expected = entry.getValue();
            Object actual = after.get(entry.getKey());
            if (expected instanceof BigDecimal expectedDecimal && actual instanceof BigDecimal actualDecimal) {
                assertEquals(0, expectedDecimal.compareTo(actualDecimal),
                        "Column " + entry.getKey() + " changed");
            } else {
                assertEquals(String.valueOf(expected), String.valueOf(actual),
                        "Column " + entry.getKey() + " changed");
            }
        }
    }
}
