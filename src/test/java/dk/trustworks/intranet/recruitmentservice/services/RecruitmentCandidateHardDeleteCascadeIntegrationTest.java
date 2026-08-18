package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidateDeletion;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Change C3/C4 against a real database: the cascade on a candidate that is
 * NOT the easy case.
 *
 * <p>The owner's refusal list is hired-or-signed and nothing else, so a
 * candidate with an application, an interview, a scorecard, a whole Method B
 * scheduling run, consents, answers on both legs of the V437 XOR, a dossier
 * with revisions and appendices, a record check, a pending email, a Slack
 * discussion thread, a referral and an Airtable import row is <em>deleted</em>,
 * not refused. Seven of the eight direct foreign keys are
 * {@code ON DELETE RESTRICT}, so getting the order wrong is not a subtle bug:
 * the statement fails, and it fails after the Slack cards have already been
 * redacted and the Graph invitations already cancelled.</p>
 *
 * <p>Everything is seeded with native SQL against the migration DDL rather
 * than through the services, so the fixture exercises the constraint graph as
 * the database actually declares it — including the rows no service would
 * plausibly create together.</p>
 *
 * <h3>How to actually run this</h3>
 * It is a {@code @QuarkusTest}, so the CI deploy gate
 * ({@code -DexcludedGroups=io.quarkus.test.junit.QuarkusTest}) skips it and
 * it proves nothing until someone points it at a database. Verified working
 * 2026-08-19 against a throwaway clone of the local Docker DB:
 * <pre>
 * ./mvnw test -Dtest='RecruitmentCandidateHardDeleteCascadeIntegrationTest' -Dsurefire.forkCount=1 -Dsurefire.maxHeap=2g \
 *   -Dquarkus.datasource.jdbc.url="jdbc:mariadb://127.0.0.1:3306/&lt;schema&gt;?collation=utf8mb4_general_ci" \
 *   -Dquarkus.datasource.username=root -Dquarkus.datasource.password=&lt;pw&gt; \
 *   -Dquarkus.datasource.health.jdbc.url="jdbc:mariadb://127.0.0.1:3306/&lt;schema&gt;?collation=utf8mb4_general_ci" \
 *   -Dquarkus.datasource.health.username=root -Dquarkus.datasource.health.password=&lt;pw&gt; \
 *   -Dquarkus.flyway.migrate-at-start=false -Dquarkus.scheduler.enabled=false \
 *   -Dcvtool.username=test -Dcvtool.password=test
 * </pre>
 * {@code forkCount=1} is required whenever {@code -Dtest=} names more than one
 * {@code @QuarkusTest} class: parallel forks collide on port 8081 and surefire
 * reports the clash as a skip, not a failure. Never raise {@code forkCount}
 * and {@code maxHeap} together — peak RAM is their product.
 */
@QuarkusTest
class RecruitmentCandidateHardDeleteCascadeIntegrationTest {

    @Inject
    CandidateService candidateService;

    @Inject
    RecruitmentCandidateDeleteCascade cascade;

    @Inject
    RecruitmentCandidateHardDeleteService hardDeleteService;

    @Inject
    EntityManager em;

    private final String actor = UUID.randomUUID().toString();
    private final List<String> candidateUuids = new ArrayList<>();
    private final List<String> ledgerUuids = new ArrayList<>();

    private String practiceUuid;
    private String positionUuid;
    private String importRunUuid;
    private String referralUuid;

    @BeforeEach
    void seedFixtures() {
        practiceUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        importRunUuid = UUID.randomUUID().toString();
        referralUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            insertPractice(practiceUuid);
            insertPosition(positionUuid, practiceUuid);
            insertImportRun(importRunUuid);
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String candidateUuid : candidateUuids) {
                for (String sql : List.of(
                        "DELETE FROM recruitment_scorecards WHERE interview_uuid IN "
                                + "(SELECT uuid FROM recruitment_interviews WHERE application_uuid IN "
                                + "(SELECT uuid FROM recruitment_applications WHERE candidate_uuid = :c))",
                        "DELETE FROM recruitment_interviews WHERE application_uuid IN "
                                + "(SELECT uuid FROM recruitment_applications WHERE candidate_uuid = :c)",
                        "DELETE FROM recruitment_calendar_hold WHERE slot_uuid IN (SELECT uuid FROM "
                                + "recruitment_proposed_slot WHERE request_uuid IN (SELECT uuid FROM "
                                + "recruitment_scheduling_request WHERE application_uuid IN (SELECT uuid "
                                + "FROM recruitment_applications WHERE candidate_uuid = :c)))",
                        "DELETE FROM recruitment_slot_approval WHERE slot_uuid IN (SELECT uuid FROM "
                                + "recruitment_proposed_slot WHERE request_uuid IN (SELECT uuid FROM "
                                + "recruitment_scheduling_request WHERE application_uuid IN (SELECT uuid "
                                + "FROM recruitment_applications WHERE candidate_uuid = :c)))",
                        "DELETE FROM recruitment_availability_constraint WHERE evidence_uuid IN "
                                + "(SELECT uuid FROM recruitment_availability_evidence WHERE request_uuid "
                                + "IN (SELECT uuid FROM recruitment_scheduling_request WHERE "
                                + "application_uuid IN (SELECT uuid FROM recruitment_applications "
                                + "WHERE candidate_uuid = :c)))",
                        "DELETE FROM recruitment_availability_evidence WHERE request_uuid IN (SELECT uuid "
                                + "FROM recruitment_scheduling_request WHERE application_uuid IN (SELECT "
                                + "uuid FROM recruitment_applications WHERE candidate_uuid = :c))",
                        "DELETE FROM recruitment_proposed_slot WHERE request_uuid IN (SELECT uuid FROM "
                                + "recruitment_scheduling_request WHERE application_uuid IN (SELECT uuid "
                                + "FROM recruitment_applications WHERE candidate_uuid = :c))",
                        "DELETE FROM recruitment_option_batch WHERE request_uuid IN (SELECT uuid FROM "
                                + "recruitment_scheduling_request WHERE application_uuid IN (SELECT uuid "
                                + "FROM recruitment_applications WHERE candidate_uuid = :c))",
                        "DELETE FROM recruitment_scheduling_outbox WHERE request_uuid IN (SELECT uuid FROM "
                                + "recruitment_scheduling_request WHERE application_uuid IN (SELECT uuid "
                                + "FROM recruitment_applications WHERE candidate_uuid = :c))",
                        "DELETE FROM recruitment_scheduling_request WHERE application_uuid IN "
                                + "(SELECT uuid FROM recruitment_applications WHERE candidate_uuid = :c)",
                        "DELETE FROM recruitment_application_answers WHERE candidate_uuid = :c "
                                + "OR application_uuid IN (SELECT uuid FROM recruitment_applications "
                                + "WHERE candidate_uuid = :c)",
                        "DELETE FROM recruitment_slack_threads WHERE application_uuid IN "
                                + "(SELECT uuid FROM recruitment_applications WHERE candidate_uuid = :c)",
                        "DELETE FROM recruitment_applications WHERE candidate_uuid = :c",
                        "DELETE FROM candidate_dossier_appendices WHERE dossier_uuid IN "
                                + "(SELECT uuid FROM candidate_dossiers WHERE candidate_uuid = :c)",
                        "DELETE FROM candidate_dossier_revisions WHERE dossier_uuid IN "
                                + "(SELECT uuid FROM candidate_dossiers WHERE candidate_uuid = :c)",
                        "DELETE FROM candidate_dossiers WHERE candidate_uuid = :c",
                        "DELETE FROM recruitment_consents WHERE candidate_uuid = :c",
                        "DELETE FROM recruitment_pending_emails WHERE candidate_uuid = :c",
                        "DELETE FROM recruitment_record_checks WHERE candidate_uuid = :c",
                        "DELETE FROM recruitment_discussion_threads WHERE candidate_uuid = :c",
                        "DELETE FROM onboarding_upload_submissions WHERE candidate_uuid = :c",
                        "DELETE FROM onboarding_upload_tokens WHERE candidate_uuid = :c",
                        "DELETE FROM recruitment_events WHERE candidate_uuid = :c",
                        "DELETE FROM recruitment_candidates WHERE uuid = :c")) {
                    em.createNativeQuery(sql).setParameter("c", candidateUuid).executeUpdate();
                }
            }
            em.createNativeQuery("DELETE FROM recruitment_referrals WHERE uuid = :r")
                    .setParameter("r", referralUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_airtable_records WHERE run_uuid = :r")
                    .setParameter("r", importRunUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_airtable_import_runs WHERE uuid = :r")
                    .setParameter("r", importRunUuid).executeUpdate();
            if (!ledgerUuids.isEmpty()) {
                em.createNativeQuery(
                                "DELETE FROM recruitment_candidate_deletions WHERE uuid IN :l")
                        .setParameter("l", ledgerUuids).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM recruitment_events WHERE position_uuid = :p")
                    .setParameter("p", positionUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid = :p")
                    .setParameter("p", positionUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM practice WHERE uuid = :pr")
                    .setParameter("pr", practiceUuid).executeUpdate();
        });
        candidateUuids.clear();
        ledgerUuids.clear();
    }

    // ---- The cascade -------------------------------------------------------------

    @Test
    void aFullyLoadedCandidate_deletesWithoutAnyForeignKeyViolation_andLeavesNoOrphans() {
        String candidateUuid = createCandidateWithApplication();
        String applicationUuid = singleApplicationOf(candidateUuid);
        seedTheWholePipeline(candidateUuid, applicationUuid);

        // The assertion of record: this call must not throw. Every direct FK
        // bar the discussion thread is ON DELETE RESTRICT, so a missing or
        // mis-ordered leg surfaces here as a constraint violation.
        String ledgerUuid = cascade.openLedger(
                candidateUuid, actor, "Duplicate row created during the Airtable import");
        cascade.recordExternalRedaction(ledgerUuid, Map.of(),
                Map.of("slackThreadRepliesAndDmsRetained", "documented residual"));
        RecruitmentCandidateDeleteCascade.CascadeResult result =
                cascade.deleteCandidate(candidateUuid, ledgerUuid);
        ledgerUuids.add(result.ledgerUuid());

        assertNull(RecruitmentCandidate.findById(candidateUuid), "the candidate row must be gone");

        // No orphans anywhere the candidate was reachable from.
        assertEquals(0L, count("recruitment_applications", "candidate_uuid", candidateUuid));
        assertEquals(0L, count("recruitment_consents", "candidate_uuid", candidateUuid));
        assertEquals(0L, count("recruitment_pending_emails", "candidate_uuid", candidateUuid));
        assertEquals(0L, count("recruitment_record_checks", "candidate_uuid", candidateUuid));
        assertEquals(0L, count("recruitment_discussion_threads", "candidate_uuid", candidateUuid));
        assertEquals(0L, count("candidate_dossiers", "candidate_uuid", candidateUuid));
        assertEquals(0L, count("recruitment_events", "candidate_uuid", candidateUuid));
        assertEquals(0L, count("onboarding_upload_tokens", "candidate_uuid", candidateUuid));
        assertEquals(0L, count("onboarding_upload_submissions", "candidate_uuid", candidateUuid));
        assertEquals(0L, count("recruitment_application_answers", "candidate_uuid", candidateUuid),
                "the candidate leg of the V437 XOR");

        assertEquals(0L, count("recruitment_application_answers", "application_uuid", applicationUuid),
                "and the application leg — both, or the answers survive the delete");
        assertEquals(0L, count("recruitment_interviews", "application_uuid", applicationUuid));
        assertEquals(0L, count("recruitment_scheduling_request", "application_uuid", applicationUuid));
        assertEquals(0L, count("recruitment_slack_threads", "application_uuid", applicationUuid));

        assertEquals(0L, scalar(
                "SELECT COUNT(*) FROM recruitment_scorecards s JOIN recruitment_interviews i "
                        + "ON s.interview_uuid = i.uuid WHERE i.application_uuid = :v",
                applicationUuid), "scorecards must go with their interview");
        assertEquals(0L, scalar("SELECT COUNT(*) FROM recruitment_proposed_slot WHERE uuid = :v",
                slotUuid), "the Method B slot family must be gone");
        assertEquals(0L, scalar("SELECT COUNT(*) FROM recruitment_calendar_hold WHERE slot_uuid = :v",
                slotUuid), "and the calendar holds that RESTRICT it");
    }

    @Test
    void theReferralSurvivesUnlinkedAndScrubbed_becauseItIsAnotherEmployeesRecord() {
        String candidateUuid = createCandidateWithApplication();
        QuarkusTransaction.requiringNew().run(() -> insertReferral(referralUuid, candidateUuid));

        RecruitmentCandidateDeleteCascade.CascadeResult result = cascade.deleteCandidate(
                candidateUuid,
                cascade.openLedger(candidateUuid, actor, "Mis-created from a duplicate referral"));
        ledgerUuids.add(result.ledgerUuid());

        assertEquals(1L, scalar("SELECT COUNT(*) FROM recruitment_referrals WHERE uuid = :v",
                referralUuid), "the referral is the referrer's record — it must not be deleted");
        assertEquals(0L, scalar("SELECT COUNT(*) FROM recruitment_referrals "
                + "WHERE uuid = :v AND candidate_uuid IS NOT NULL", referralUuid),
                "but its candidate link must be nulled, or the RESTRICTing FK blocks the delete");
        assertEquals(0L, scalar("SELECT COUNT(*) FROM recruitment_referrals "
                + "WHERE uuid = :v AND email IS NOT NULL", referralUuid),
                "and the candidate's own columns on it must be scrubbed — otherwise the "
                        + "delete leaves their name and email sitting in \"My referrals\"");
    }

    @Test
    void theAirtableImportRowSurvivesUnlinked_soAReimportDoesNotRecreateTheCandidate() {
        String candidateUuid = createCandidateWithApplication();
        String recordId = "rec" + UUID.randomUUID().toString().substring(0, 12);
        QuarkusTransaction.requiringNew().run(() ->
                insertAirtableRecord(recordId, importRunUuid, candidateUuid));

        RecruitmentCandidateDeleteCascade.CascadeResult result = cascade.deleteCandidate(
                candidateUuid,
                cascade.openLedger(candidateUuid, actor,
                        "Imported twice from the same Airtable base"));
        ledgerUuids.add(result.ledgerUuid());

        assertEquals(1L, scalar("SELECT COUNT(*) FROM recruitment_airtable_records "
                + "WHERE airtable_record_id = :v", recordId),
                "the import row is the cross-run idempotency key — deleting it makes the next "
                        + "import recreate the candidate that was just removed");
        assertEquals(0L, scalar("SELECT COUNT(*) FROM recruitment_airtable_records "
                        + "WHERE airtable_record_id = :v AND candidate_uuid IS NOT NULL", recordId));
    }

    // ---- The ledger ---------------------------------------------------------------

    @Test
    void theLedgerRowSurvivesTheDelete_andCarriesNoName() {
        String candidateUuid = createCandidateWithApplication();
        RecruitmentCandidate before = RecruitmentCandidate.findById(candidateUuid);
        String firstName = before.getFirstName();
        String lastName = before.getLastName();

        RecruitmentCandidateDeleteCascade.CascadeResult result = cascade.deleteCandidate(
                candidateUuid,
                cascade.openLedger(candidateUuid, actor,
                        "Created by mistake — wrong position picked"));
        ledgerUuids.add(result.ledgerUuid());

        RecruitmentCandidateDeletion ledger =
                RecruitmentCandidateDeletion.findById(result.ledgerUuid());
        assertNotNull(ledger, "the ledger is the only surviving record — the candidate's own "
                + "events, which is where everything else in this module audits itself, went "
                + "with them");
        assertEquals(candidateUuid, ledger.getCandidateUuid());
        assertEquals(actor, ledger.getActorUuid());
        assertEquals("Created by mistake — wrong position picked", ledger.getReason());
        assertTrue(ledger.getDeletedCounts().contains("recruitment_candidates"),
                "per-table counts, so an operator can tell a fat-finger undo from a delete "
                        + "that took a whole pipeline: " + ledger.getDeletedCounts());

        String whole = ledger.getUuid() + ledger.getCandidateUuid() + ledger.getActorUuid()
                + ledger.getReason() + ledger.getDeletedCounts()
                + (ledger.getResidue() == null ? "" : ledger.getResidue());
        assertFalse(whole.contains(firstName),
                "storing the name here would defeat the deletion this row records");
        assertFalse(whole.contains(lastName));
    }

    /**
     * The residue leg specifically (verification finding, 2026-08-19). The
     * test above drives {@code cascade.deleteCandidate} with an empty residue
     * map, so it could never have caught this: the offending value is added
     * by the ORCHESTRATOR, from {@code sharepoint_folder_path} — the one
     * column {@code RecruitmentCandidate.anonymize} nulls with the comment
     * "contains the candidate's name". This drives the whole
     * {@code hardDelete} and re-reads the persisted JSON.
     */
    @Test
    void theLedgerResidueCarriesNoNameEvenWhenASharePointFolderSurvives() {
        String candidateUuid = createCandidateWithApplication();
        RecruitmentCandidate before = RecruitmentCandidate.findById(candidateUuid);
        String firstName = before.getFirstName();
        String lastName = before.getLastName();
        String folderPath = "/sites/HR/Shared Documents/Recruitment/2026/"
                + firstName + " " + lastName;
        QuarkusTransaction.requiringNew().run(() -> exec(
                "UPDATE recruitment_candidates SET sharepoint_folder_path = :p WHERE uuid = :c",
                Map.of("p", folderPath, "c", candidateUuid)));

        // The row was changed under the session by native SQL, so drop the
        // first-level cache before re-reading — otherwise hardDelete gets the
        // stale instance whose sharepoint_folder_path is still null.
        em.clear();
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        assertEquals(folderPath, candidate.getSharepointFolderPath());
        RecruitmentCandidateHardDeleteService.HardDeleteSummary summary =
                hardDeleteService.hardDelete(candidate, actor, "Created by mistake, undoing it");
        ledgerUuids.add(summary.ledgerUuid());

        assertTrue(summary.residue().containsKey("sharepointFolderRetained"),
                "the folder really does survive — the delete must still say so");
        assertFalse(summary.residue().toString().contains(folderPath),
                "the API response must not echo the raw path either");

        RecruitmentCandidateDeletion ledger =
                RecruitmentCandidateDeletion.findById(summary.ledgerUuid());
        assertNotNull(ledger);
        String residue = ledger.getResidue() == null ? "" : ledger.getResidue();
        assertTrue(residue.contains("pathSha256"),
                "the redacted handle is what should have been persisted: " + residue);
        assertFalse(residue.contains(firstName),
                "recruitment_candidate_deletions has no FK, is excluded from the prod -> "
                        + "staging sync and is never cleaned — a name written here is "
                        + "permanent, and its own migration header forbids it");
        assertFalse(residue.contains(lastName));
        assertFalse(residue.contains(folderPath));
    }

    // ---- The two refusals ----------------------------------------------------------

    @Test
    void aHiredCandidate_isRefusedWith409_andNothingIsDeleted() {
        String candidateUuid = createCandidateWithApplication();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE recruitment_candidates SET status = 'HIRED' WHERE uuid = :c")
                .setParameter("c", candidateUuid).executeUpdate());
        RecruitmentCandidate candidate = reload(candidateUuid);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> hardDeleteService.hardDelete(candidate, actor, "Trying to undo a real hire"));

        assertEquals(409, thrown.getResponse().getStatus());
        assertEquals(RecruitmentCandidateHardDeleteService.REFUSAL_HIRED_OR_CONVERTED,
                ((Map<?, ?>) thrown.getResponse().getEntity()).get("error"));
        assertNotNull(RecruitmentCandidate.findById(candidateUuid), "nothing may have been deleted");
        assertEquals(1L, count("recruitment_applications", "candidate_uuid", candidateUuid));
    }

    @Test
    void aSignedDossierRevision_isRefusedWith409_andNothingIsDeleted() {
        String candidateUuid = createCandidateWithApplication();
        String dossierUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            insertDossier(dossierUuid, candidateUuid);
            insertSignedRevision(dossierUuid);
        });
        RecruitmentCandidate candidate = reload(candidateUuid);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> hardDeleteService.hardDelete(candidate, actor,
                        "Trying to remove a candidate who already signed"));

        assertEquals(409, thrown.getResponse().getStatus());
        assertEquals(RecruitmentCandidateHardDeleteService.REFUSAL_SIGNED,
                ((Map<?, ?>) thrown.getResponse().getEntity()).get("error"));
        assertEquals(1L, count("candidate_dossiers", "candidate_uuid", candidateUuid));
        assertNotNull(RecruitmentCandidate.findById(candidateUuid));
    }

    // ---- Fixture ---------------------------------------------------------------------

    private String slotUuid;

    private String createCandidateWithApplication() {
        RecruitmentPosition position = RecruitmentPosition.findById(positionUuid);
        CandidateResponse created = candidateService.createCandidate(
                new CandidateRequest(
                        "Cascade", "Fixture" + UUID.randomUUID().toString().substring(0, 6),
                        "cascade-" + UUID.randomUUID() + "@example.com",
                        null, null, null, null, null, null,
                        CandidateSource.OTHER,
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null),
                UUID.fromString(actor), position);
        candidateUuids.add(created.uuid());
        return created.uuid();
    }

    private String singleApplicationOf(String candidateUuid) {
        return (String) em.createNativeQuery(
                        "SELECT uuid FROM recruitment_applications WHERE candidate_uuid = :c")
                .setParameter("c", candidateUuid)
                .getSingleResult();
    }

    /** Everything the owner decided must NOT be a refusal. */
    private void seedTheWholePipeline(String candidateUuid, String applicationUuid) {
        String interviewUuid = UUID.randomUUID().toString();
        String requestUuid = UUID.randomUUID().toString();
        String evidenceUuid = UUID.randomUUID().toString();
        String dossierUuid = UUID.randomUUID().toString();
        String tokenUuid = UUID.randomUUID().toString();
        slotUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            exec("INSERT INTO recruitment_interviews (uuid, application_uuid, kind, round, "
                    + "scheduled_at, duration_minutes, interviewer_uuids, status, graph_event_id, "
                    + "created_at, updated_at, created_by) VALUES (:i, :a, 'ROUND', 1, NOW(), 60, "
                    + "'[]', 'SCHEDULED', NULL, NOW(), NOW(), 'test')",
                    Map.of("i", interviewUuid, "a", applicationUuid));
            exec("INSERT INTO recruitment_scorecards (uuid, interview_uuid, interviewer_uuid, "
                    + "scores, recommendation, submitted_at, created_at, updated_at, created_by) "
                    + "VALUES (UUID(), :i, :u, '{}', 'YES', NOW(), NOW(), NOW(), 'test')",
                    Map.of("i", interviewUuid, "u", actor));

            exec("INSERT INTO recruitment_scheduling_request (uuid, application_uuid, "
                    + "recruiter_uuid, kind, interviewer_uuids, window_start, window_end, "
                    + "automation_deadline, created_by) VALUES (:r, :a, :u, 'ROUND', '[]', "
                    + "CURDATE(), CURDATE(), NOW(), 'test')",
                    Map.of("r", requestUuid, "a", applicationUuid, "u", actor));
            exec("INSERT INTO recruitment_proposed_slot (uuid, request_uuid, option_no, "
                    + "slot_start, slot_end) VALUES (:s, :r, 1, NOW(), "
                    + "DATE_ADD(NOW(), INTERVAL 1 HOUR))",
                    Map.of("s", slotUuid, "r", requestUuid));
            exec("INSERT INTO recruitment_slot_approval (uuid, slot_uuid, user_uuid) "
                    + "VALUES (UUID(), :s, :u)", Map.of("s", slotUuid, "u", actor));
            exec("INSERT INTO recruitment_calendar_hold (uuid, slot_uuid, owner_kind, user_uuid, "
                    + "mailbox) VALUES (UUID(), :s, 'USER', :u, 'fixture@example.com')",
                    Map.of("s", slotUuid, "u", actor));
            exec("INSERT INTO recruitment_option_batch (uuid, request_uuid, token_hash, "
                    + "expires_at) VALUES (UUID(), :r, REPEAT('a', 64), "
                    + "DATE_ADD(NOW(), INTERVAL 3 DAY))", Map.of("r", requestUuid));
            exec("INSERT INTO recruitment_scheduling_outbox (uuid, request_uuid, action, "
                    + "idempotency_key, next_attempt_at) VALUES (UUID(), :r, 'CREATE_HOLD', :k, "
                    + "NOW())", Map.of("r", requestUuid, "k", "fixture-" + requestUuid));
            exec("INSERT INTO recruitment_availability_evidence (uuid, request_uuid, user_uuid, "
                    + "source_type, intent) VALUES (:e, :r, :u, 'RECRUITER', 'UNKNOWN')",
                    Map.of("e", evidenceUuid, "r", requestUuid, "u", actor));
            exec("INSERT INTO recruitment_availability_constraint (uuid, evidence_uuid, type, "
                    + "start_at, end_at) VALUES (UUID(), :e, 'BUSY', NOW(), "
                    + "DATE_ADD(NOW(), INTERVAL 1 HOUR))", Map.of("e", evidenceUuid));

            // 'TALENT_POOL_RETENTION', not 'TALENT_POOL': chk_rcon_kind_enum
            // (V436, widened by V479) is a real CHECK constraint and rejects
            // anything else. Caught the first time this class was ever
            // executed — it had only ever been compiled until 2026-08-19.
            exec("INSERT INTO recruitment_consents (uuid, candidate_uuid, kind, created_at, "
                    + "updated_at, created_by) VALUES (UUID(), :c, 'TALENT_POOL_RETENTION', "
                    + "NOW(), NOW(), 'test')", Map.of("c", candidateUuid));
            exec("INSERT INTO recruitment_application_answers (uuid, application_uuid, "
                    + "question_key, answer, created_at) VALUES (UUID(), :a, 'why_us', 'x', NOW(3))",
                    Map.of("a", applicationUuid));
            exec("INSERT INTO recruitment_application_answers (uuid, candidate_uuid, "
                    + "question_key, answer, created_at) VALUES (UUID(), :c, 'why_us', 'x', NOW(3))",
                    Map.of("c", candidateUuid));
            exec("INSERT INTO recruitment_pending_emails (uuid, candidate_uuid, template_key, "
                    + "reason, to_email, subject, body, created_at, updated_at, created_by) "
                    + "VALUES (UUID(), :c, 'ART14_NOTICE', 'ART14', 'x@example.com', 's', 'b', "
                    + "NOW(), NOW(), 'test')", Map.of("c", candidateUuid));
            exec("INSERT INTO recruitment_record_checks (uuid, candidate_uuid, drawn_at, "
                    + "rate_applied, selected, created_at, updated_at, created_by) VALUES (UUID(), "
                    + ":c, NOW(), 10, 0, NOW(), NOW(), 'test')", Map.of("c", candidateUuid));
            exec("INSERT INTO recruitment_discussion_threads (uuid, candidate_uuid, channel_id, "
                    + "root_ts, created_at, updated_at, created_by) VALUES (UUID(), :c, 'C123', "
                    + "'1.2', NOW(), NOW(), 'test')", Map.of("c", candidateUuid));
            exec("INSERT INTO recruitment_slack_threads (application_uuid, channel_id, root_ts) "
                    + "VALUES (:a, 'C123', '1.3')", Map.of("a", applicationUuid));

            insertDossier(dossierUuid, candidateUuid);
            exec("INSERT INTO candidate_dossier_revisions (uuid, dossier_uuid, version_number, "
                    + "kind, placeholder_values_snapshot, signers_config_snapshot, "
                    + "appendices_snapshot, recipient_email, sent_by_useruuid) VALUES (UUID(), :d, "
                    + "1, 'REVIEW_PDF', '{}', '[]', '[]', 'x@example.com', :u)",
                    Map.of("d", dossierUuid, "u", actor));
            exec("INSERT INTO candidate_dossier_appendices (uuid, dossier_uuid, file_uuid, "
                    + "original_filename, uploaded_by_useruuid) VALUES (UUID(), :d, UUID(), "
                    + "'appendix.pdf', :u)", Map.of("d", dossierUuid, "u", actor));

            exec("INSERT INTO onboarding_upload_tokens (uuid, candidate_uuid, expires_at, "
                    + "created_by_useruuid) VALUES (:t, :c, DATE_ADD(NOW(), INTERVAL 7 DAY), :u)",
                    Map.of("t", tokenUuid, "c", candidateUuid, "u", actor));
            exec("INSERT INTO onboarding_upload_submissions (uuid, token_uuid, document_type, "
                    + "candidate_uuid, storage_target, s3_file_uuid, original_filename, "
                    + "content_type, file_size_bytes) VALUES (UUID(), :t, 'DRIVERS_LICENSE', :c, "
                    + "'S3', UUID(), 'id.pdf', 'application/pdf', 100)",
                    Map.of("t", tokenUuid, "c", candidateUuid));

            insertReferral(referralUuid, candidateUuid);
        });
    }

    // ---- SQL helpers -------------------------------------------------------------------

    private void exec(String sql, Map<String, Object> params) {
        var query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        query.executeUpdate();
    }

    private void insertDossier(String dossierUuid, String candidateUuid) {
        exec("INSERT INTO candidate_dossiers (uuid, candidate_uuid, template_uuid, status) "
                + "VALUES (:d, :c, UUID(), 'OPEN')",
                Map.of("d", dossierUuid, "c", candidateUuid));
    }

    private void insertSignedRevision(String dossierUuid) {
        exec("INSERT INTO candidate_dossier_revisions (uuid, dossier_uuid, version_number, kind, "
                + "placeholder_values_snapshot, signers_config_snapshot, appendices_snapshot, "
                + "signing_case_key, recipient_email, sent_by_useruuid) VALUES (UUID(), :d, 1, "
                + "'SIGNATURE', '{}', '[]', '[]', :k, 'x@example.com', :u)",
                Map.of("d", dossierUuid, "k", "case-" + dossierUuid, "u", actor));
    }

    private void insertReferral(String uuid, String candidateUuid) {
        exec("INSERT INTO recruitment_referrals (uuid, referrer_uuid, referrer_relation, "
                + "candidate_name, email, why_text, submitted_at, candidate_uuid, created_at, "
                + "updated_at, created_by) VALUES (:r, :u, 'FORMER_COLLEAGUE', 'Cascade Fixture', "
                + "'referred@example.com', 'because', NOW(3), :c, NOW(), NOW(), 'test')",
                Map.of("r", uuid, "u", actor, "c", candidateUuid));
    }

    private void insertAirtableRecord(String recordId, String runUuid, String candidateUuid) {
        exec("INSERT INTO recruitment_airtable_records (airtable_record_id, airtable_table, "
                + "run_uuid, candidate_uuid, status, imported_at) VALUES (:id, 'Candidates', :r, "
                + ":c, 'IMPORTED', NOW())",
                Map.of("id", recordId, "r", runUuid, "c", candidateUuid));
    }

    private void insertImportRun(String uuid) {
        exec("INSERT INTO recruitment_airtable_import_runs (uuid, mode, status, started_at, "
                + "started_by) VALUES (:u, 'DRY_RUN', 'COMPLETED', NOW(), 'test')",
                Map.of("u", uuid));
    }

    private void insertPractice(String uuid) {
        exec("INSERT INTO practice (code, uuid, name, active, sort_order, created_at, updated_at, "
                + "created_by) VALUES (:code, :uuid, 'Cascade Fixture', 1, 999, NOW(), NOW(), "
                + "'test')",
                Map.of("code", "D" + uuid.substring(0, 7), "uuid", uuid));
    }

    private void insertPosition(String uuid, String practiceUuid) {
        exec("INSERT INTO recruitment_positions (uuid, title, hiring_track, practice_uuid, "
                + "stage_set, demand_rag, status, opened_at, created_at, updated_at, created_by) "
                + "VALUES (:uuid, 'Cascade req', 'PRACTICE_TEAM', :practice, "
                + "'[\"SCREENING\",\"INTERVIEW_1\",\"INTERVIEW_2\",\"OFFER\",\"HIRED\"]', "
                + "'GREEN', 'OPEN', NOW(3), NOW(), NOW(), 'test')",
                Map.of("uuid", uuid, "practice", practiceUuid));
    }

    private static RecruitmentCandidate reload(String candidateUuid) {
        return QuarkusTransaction.requiringNew()
                .call(() -> RecruitmentCandidate.findById(candidateUuid));
    }

    private long count(String table, String column, String value) {
        return scalar("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :v", value);
    }

    private long scalar(String sql, String value) {
        return ((Number) em.createNativeQuery(sql).setParameter("v", value)
                .getSingleResult()).longValue();
    }
}
