package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P19 DoD: the zero-PII scan. A maximally rich fixture — candidate row
 * with every PII column filled, events with pii on BOTH legs (candidate-
 * subject including {@code AI_*} types, and the referral-era events that
 * carry only {@code payload.referral_uuid}), form answers on both
 * ownership legs, a referral row, pending-email snapshots in every
 * status, a dossier with an immutable revision snapshot and an appendix,
 * and a live consent token — is anonymized, then EVERY table is scanned
 * for the sentinel: nothing personal may remain anywhere (spec §4.1
 * "Nothing personal may exist anywhere else").
 * <p>
 * The structural skeleton must survive: event rows/order/payloads,
 * answer rows (keys), the referral's status machine, application rows —
 * statistics keep working after erasure.
 * <p>
 * S3 is mocked; the S3 leg is asserted as "the anonymizer ordered the
 * deletion of exactly this candidate's files" (the delete plumbing itself
 * is a one-liner on the shared {@code S3FileService}).
 */
@QuarkusTest
class RecruitmentAnonymizerZeroPiiIntegrationTest {

    /** Deliberately distinctive — the scan greps every table for this. */
    private static final String SENTINEL = "ZPII_SENTINEL_P19";

    @Inject
    RecruitmentAnonymizerService anonymizer;

    @Inject
    EntityManager em;

    @InjectMock
    RecruitmentS3StorageService storageService;

    private String candidateUuid;
    private String applicationUuid;
    private String positionUuid;
    private String referralUuid;
    private String dossierUuid;
    private String revisionUuid;
    private final String actor = UUID.randomUUID().toString();

    @BeforeEach
    void seedRichFixture() {
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        referralUuid = UUID.randomUUID().toString();
        dossierUuid = UUID.randomUUID().toString();
        revisionUuid = UUID.randomUUID().toString();
        when(storageService.deleteAllCandidateFiles(any(UUID.class))).thenReturn(3);

        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            INSERT INTO recruitment_positions
                                (uuid, title, hiring_track, stage_set, demand_rag, status,
                                 opened_at, created_at, updated_at, created_by)
                            VALUES (:uuid, 'ZeroPii Position', 'STAFF_ROLE',
                                    '["SCREENING","OFFER","HIRED"]', 'GREEN', 'OPEN',
                                    NOW(), NOW(), NOW(), 'test')
                            """)
                    .setParameter("uuid", positionUuid).executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO recruitment_candidates
                                (uuid, first_name, last_name, email, phone, linkedin_url, notes,
                                 decline_reason, status, pool_status, source, source_detail,
                                 external_referrer_name, tags, education_level, education_other,
                                 experience_level, specializations, languages, current_employer,
                                 sharepoint_folder_path, retention_deadline,
                                 art14_required, art14_deadline, process_ended_at,
                                 created_by_useruuid, created_at, updated_at)
                            VALUES (:uuid, :s, :s, :email, :s, :s, :s,
                                    :s, 'POOLED', 'SILVER_MEDALIST', 'REFERRAL',
                                    JSON_OBJECT('referenceName', :s),
                                    :s, JSON_ARRAY(:s), 'MASTER', :s,
                                    'SENIOR', JSON_ARRAY('PM'), JSON_ARRAY(:s), :s,
                                    :s, NOW(), 1, NOW(), NOW(),
                                    :actor, NOW(), NOW())
                            """)
                    .setParameter("uuid", candidateUuid)
                    .setParameter("s", SENTINEL)
                    .setParameter("email", SENTINEL + "@example.invalid")
                    .setParameter("actor", actor).executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO recruitment_applications
                                (uuid, candidate_uuid, position_uuid, stage, stage_entered_at,
                                 created_at, updated_at, created_by)
                            VALUES (:uuid, :candidate, :position, 'SCREENING', NOW(),
                                    NOW(), NOW(), 'test')
                            """)
                    .setParameter("uuid", applicationUuid)
                    .setParameter("candidate", candidateUuid)
                    .setParameter("position", positionUuid).executeUpdate();
            // Answers: BOTH ownership legs (V437).
            insertAnswer(applicationUuid, null, "WHY_TRUSTWORKS");
            insertAnswer(null, candidateUuid, "STRENGTHS");
            // Referral row with its own PII columns.
            em.createNativeQuery("""
                            INSERT INTO recruitment_referrals
                                (uuid, referrer_uuid, referrer_relation, external_referrer_name,
                                 candidate_name, linkedin_url, email, why_text, candidate_uuid,
                                 status, submitted_at, version, created_at, updated_at, created_by)
                            VALUES (:uuid, :referrer, 'COLLEAGUE', :s,
                                    :s, :s, :email, :s, :candidate,
                                    'CONVERTED', NOW(), 0, NOW(), NOW(), 'test')
                            """)
                    .setParameter("uuid", referralUuid)
                    .setParameter("referrer", actor)
                    .setParameter("s", SENTINEL)
                    .setParameter("email", SENTINEL + "@example.invalid")
                    .setParameter("candidate", candidateUuid).executeUpdate();
            // Events: candidate-subject leg incl. an AI type…
            insertEvent("NOTE_ADDED", candidateUuid, null,
                    "{\"field\":\"GENERAL\"}", "{\"text\":\"" + SENTINEL + " note\"}");
            insertEvent("AI_BRIEF_GENERATED", candidateUuid, null,
                    "{\"model\":\"gpt-x\"}", "{\"brief\":\"" + SENTINEL + " brief\"}");
            insertEvent("EMAIL_SENT", candidateUuid, null,
                    "{\"template_key\":\"ACKNOWLEDGEMENT\"}",
                    "{\"subject\":\"" + SENTINEL + "\",\"body\":\"" + SENTINEL + "\"}");
            // …and the referral leg (NO candidate subject, payload.referral_uuid).
            insertEvent("REFERRAL_SUBMITTED", null, referralUuid,
                    "{\"referral_uuid\":\"" + referralUuid + "\"}",
                    "{\"candidate_name\":\"" + SENTINEL + "\",\"why\":\"" + SENTINEL + "\"}");
            insertEvent("AI_SUGGESTIONS_GENERATED", null, referralUuid,
                    "{\"referral_uuid\":\"" + referralUuid + "\",\"variant\":\"referral\"}",
                    "{\"suggestions\":\"" + SENTINEL + "\"}");
            // Pending emails: one PENDING, one APPROVED — both carry snapshots.
            insertPendingEmail("PENDING");
            insertPendingEmail("APPROVED");
            // Dossier family: live JSON, an immutable revision, an appendix.
            em.createNativeQuery("""
                            INSERT INTO candidate_dossiers
                                (uuid, candidate_uuid, template_uuid, placeholder_values_json,
                                 signers_config_json, appendices_json, status, created_at, updated_at)
                            VALUES (:uuid, :candidate, :template,
                                    JSON_OBJECT('name', :s), JSON_ARRAY(JSON_OBJECT('email', :email)),
                                    JSON_ARRAY(), 'OPEN', NOW(), NOW())
                            """)
                    .setParameter("uuid", dossierUuid)
                    .setParameter("candidate", candidateUuid)
                    .setParameter("template", UUID.randomUUID().toString())
                    .setParameter("s", SENTINEL)
                    .setParameter("email", SENTINEL + "@example.invalid").executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO candidate_dossier_revisions
                                (uuid, dossier_uuid, version_number, kind,
                                 placeholder_values_snapshot, signers_config_snapshot,
                                 appendices_snapshot, generated_pdfs_snapshot,
                                 recipient_email, sent_by_useruuid, note, created_at)
                            VALUES (:uuid, :dossier, 1, 'REVIEW_EMAIL',
                                    JSON_OBJECT('name', :s), JSON_ARRAY(JSON_OBJECT('email', :email)),
                                    JSON_ARRAY(JSON_OBJECT('filename', :s)),
                                    JSON_ARRAY(JSON_OBJECT('filename', :s, 'fileUuid', :file)),
                                    :email, :actor, :s, NOW())
                            """)
                    .setParameter("uuid", revisionUuid)
                    .setParameter("dossier", dossierUuid)
                    .setParameter("s", SENTINEL)
                    .setParameter("email", SENTINEL + "@example.invalid")
                    .setParameter("file", UUID.randomUUID().toString())
                    .setParameter("actor", actor).executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO candidate_dossier_appendices
                                (uuid, dossier_uuid, file_uuid, original_filename, display_order,
                                 sign_obligated, uploaded_by_useruuid, created_at, updated_at)
                            VALUES (:uuid, :dossier, :file, :name, 1, 0, :actor, NOW(), NOW())
                            """)
                    .setParameter("uuid", UUID.randomUUID().toString())
                    .setParameter("dossier", dossierUuid)
                    .setParameter("file", UUID.randomUUID().toString())
                    .setParameter("name", SENTINEL + "-cv.pdf")
                    .setParameter("actor", actor).executeUpdate();
            // Live consent token that must not survive.
            em.createNativeQuery("""
                            INSERT INTO recruitment_consents
                                (uuid, candidate_uuid, kind, status, token_hash, token_expires_at,
                                 created_at, updated_at, created_by)
                            VALUES (:uuid, :candidate, 'TALENT_POOL_RETENTION', 'REQUESTED',
                                    :hash, :expires, NOW(), NOW(), 'test')
                            """)
                    .setParameter("uuid", UUID.randomUUID().toString())
                    .setParameter("candidate", candidateUuid)
                    .setParameter("hash", "a".repeat(64))
                    .setParameter("expires", LocalDateTime.now().plusDays(30)).executeUpdate();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid = :c "
                            + "OR JSON_UNQUOTE(JSON_EXTRACT(payload, '$.referral_uuid')) = :r")
                    .setParameter("c", candidateUuid)
                    .setParameter("r", referralUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_pending_emails WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM candidate_dossier_appendices WHERE dossier_uuid = :d")
                    .setParameter("d", dossierUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM candidate_dossier_revisions WHERE dossier_uuid = :d")
                    .setParameter("d", dossierUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM candidate_dossiers WHERE uuid = :d")
                    .setParameter("d", dossierUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_consents WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_referrals WHERE uuid = :r")
                    .setParameter("r", referralUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_application_answers "
                            + "WHERE application_uuid = :a OR candidate_uuid = :c")
                    .setParameter("a", applicationUuid)
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_applications WHERE uuid = :a")
                    .setParameter("a", applicationUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid = :p")
                    .setParameter("p", positionUuid).executeUpdate();
        });
    }

    // ------------------------------------------------------------------
    // The scan
    // ------------------------------------------------------------------

    @Test
    void anonymize_leavesNoSentinelAnywhere_andKeepsTheSkeleton() {
        long eventsBefore = countCandidateAndReferralEvents();

        RecruitmentAnonymizerService.AnonymizationSummary summary = anonymizer.anonymize(
                candidateUuid, RecruitmentAnonymizerService.Mode.ON_REQUEST, actor);

        // ---- The zero-PII scan: sentinel gone from every table ------------
        assertEquals(0, scan("recruitment_candidates",
                "uuid = '" + candidateUuid + "'"), "candidate row");
        assertEquals(0, scan("recruitment_events",
                "candidate_uuid = '" + candidateUuid + "'"), "candidate-leg events (incl. AI_*)");
        assertEquals(0, scan("recruitment_events",
                "JSON_UNQUOTE(JSON_EXTRACT(payload, '$.referral_uuid')) = '" + referralUuid + "'"),
                "referral-leg events (REFERRAL_SUBMITTED + referral-variant AI)");
        assertEquals(0, scan("recruitment_application_answers",
                "application_uuid = '" + applicationUuid + "' OR candidate_uuid = '"
                        + candidateUuid + "'"), "answers, both ownership legs");
        assertEquals(0, scan("recruitment_referrals",
                "uuid = '" + referralUuid + "'"), "referral PII columns");
        assertEquals(0, scan("recruitment_pending_emails",
                "candidate_uuid = '" + candidateUuid + "'"), "pending-email snapshots");
        assertEquals(0, scan("candidate_dossiers",
                "uuid = '" + dossierUuid + "'"), "live dossier JSON");
        assertEquals(0, scan("candidate_dossier_revisions",
                "dossier_uuid = '" + dossierUuid + "'"), "immutable revision snapshots");
        assertEquals(0, scan("candidate_dossier_appendices",
                "dossier_uuid = '" + dossierUuid + "'"), "appendix filenames");

        // ---- The S3 leg: exactly this candidate's files were deleted ------
        ArgumentCaptor<UUID> deleted = ArgumentCaptor.forClass(UUID.class);
        verify(storageService, times(1)).deleteAllCandidateFiles(deleted.capture());
        assertEquals(candidateUuid, deleted.getValue().toString());
        assertEquals(3, summary.documentsDeleted());

        // ---- The structural skeleton survives ------------------------------
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
            assertEquals(CandidateStatus.ANONYMIZED, candidate.getStatus());
            assertNotNull(candidate.getAnonymizedAt());
            assertEquals("REFERRAL", candidate.getSource().name(), "source survives for stats");
            assertNotNull(candidate.getProcessEndedAt(), "dates survive for stats");
            assertEquals("MASTER", candidate.getEducationLevel().name());
            assertEquals(List.of("PM"), candidate.getSpecializations(),
                    "catalog codes are structural, not PII");
        });
        assertEquals(eventsBefore + 1, countCandidateAndReferralEvents(),
                "no event row is ever deleted; exactly one CANDIDATE_ANONYMIZED was appended");
        assertEquals(2L, count("SELECT COUNT(*) FROM recruitment_application_answers "
                        + "WHERE (application_uuid = :a OR candidate_uuid = :c)",
                "a", applicationUuid, "c", candidateUuid),
                "answer rows survive (keys keep counting) — only the text is gone");
        // Event payloads stay untouched; pii is the rewritten section.
        assertEquals(0L, count("SELECT COUNT(*) FROM recruitment_events "
                        + "WHERE candidate_uuid = :c AND pii_state = 'PRESENT'",
                "c", candidateUuid, null, null));
        assertEquals(0L, count("SELECT COUNT(*) FROM recruitment_consents "
                        + "WHERE candidate_uuid = :c AND token_hash IS NOT NULL",
                "c", candidateUuid, null, null), "no live consent link survives erasure");

        // ---- The bookkeeping event ----------------------------------------
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentEvent event = RecruitmentEvent
                    .<RecruitmentEvent>find("candidateUuid = ?1 and eventType = ?2",
                            candidateUuid,
                            dk.trustworks.intranet.recruitmentservice.events
                                    .RecruitmentEventType.CANDIDATE_ANONYMIZED)
                    .firstResult();
            assertNotNull(event);
            assertTrue(event.getPayload().contains("\"mode\":\"ON_REQUEST\""));
            assertEquals(actor, event.getActorUuid(), "the DPO is the audited actor");
        });
    }

    @Test
    void anonymize_isIdempotent() {
        anonymizer.anonymize(candidateUuid, RecruitmentAnonymizerService.Mode.ON_REQUEST, actor);
        RecruitmentAnonymizerService.AnonymizationSummary second = anonymizer.anonymize(
                candidateUuid, RecruitmentAnonymizerService.Mode.AUTO, null);

        assertTrue(second.alreadyAnonymized(), "a raced second run is a harmless no-op");
        assertEquals(1L, count("SELECT COUNT(*) FROM recruitment_events "
                        + "WHERE candidate_uuid = :c AND event_type = 'CANDIDATE_ANONYMIZED'",
                "c", candidateUuid, null, null), "exactly one bookkeeping event, ever");
    }

    @Test
    void hiredCandidate_isRefused() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates SET status = 'HIRED' "
                                + "WHERE uuid = :c")
                        .setParameter("c", candidateUuid).executeUpdate());
        assertThrows(BusinessRuleViolation.class, () ->
                        anonymizer.anonymize(candidateUuid,
                                RecruitmentAnonymizerService.Mode.ON_REQUEST, actor),
                "hired candidates leave the retention regime (spec §5.5)");
    }

    // ------------------------------------------------------------------
    // Fixture / scan helpers
    // ------------------------------------------------------------------

    /**
     * Concatenates every column of the matching rows and counts sentinel
     * hits — column-name independent, so a future PII column cannot dodge
     * the scan by not being listed here.
     */
    private long scan(String table, String where) {
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object[]> columns = em.createNativeQuery(
                            "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :table")
                    .setParameter("table", table).getResultList()
                    .stream().map(c -> new Object[]{c}).toList();
            String concat = columns.stream()
                    .map(c -> "IFNULL(CONVERT(`" + c[0] + "` USING utf8mb4), '')")
                    .reduce((a, b) -> a + ", " + b)
                    .map(cols -> "CONCAT_WS('|', " + cols + ")")
                    .orElseThrow();
            Number hits = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM `" + table + "` WHERE (" + where + ") AND "
                                    + concat + " LIKE :needle")
                    .setParameter("needle", "%" + SENTINEL + "%")
                    .getSingleResult();
            return hits.longValue();
        });
    }

    private long countCandidateAndReferralEvents() {
        return count("SELECT COUNT(*) FROM recruitment_events WHERE candidate_uuid = :c "
                        + "OR JSON_UNQUOTE(JSON_EXTRACT(payload, '$.referral_uuid')) = :r",
                "c", candidateUuid, "r", referralUuid);
    }

    private long count(String sql, String p1, String v1, String p2, String v2) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var query = em.createNativeQuery(sql).setParameter(p1, v1);
            if (p2 != null) {
                query.setParameter(p2, v2);
            }
            return ((Number) query.getSingleResult()).longValue();
        });
    }

    private void insertAnswer(String applicationUuid, String candidateUuid, String questionKey) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_application_answers
                            (uuid, application_uuid, candidate_uuid, question_key, answer, created_at)
                        VALUES (:uuid, :application, :candidate, :key, :answer, NOW())
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("application", applicationUuid)
                .setParameter("candidate", candidateUuid)
                .setParameter("key", questionKey)
                .setParameter("answer", SENTINEL + " long form answer")
                .executeUpdate();
    }

    private void insertEvent(String type, String candidateUuid, String referralContext,
                             String payload, String pii) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_events
                            (event_id, event_type, candidate_uuid, actor_type, occurred_at,
                             visibility, payload, pii, pii_state)
                        VALUES (:id, :type, :candidate, 'SYSTEM', NOW(3), 'NORMAL',
                                :payload, :pii, 'PRESENT')
                        """)
                .setParameter("id", UUID.randomUUID().toString())
                .setParameter("type", type)
                .setParameter("candidate", candidateUuid)
                .setParameter("payload", payload)
                .setParameter("pii", pii)
                .executeUpdate();
    }

    private void insertPendingEmail(String status) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_pending_emails
                            (uuid, candidate_uuid, application_uuid, template_uuid, template_key,
                             reason, to_email, subject, body, status, created_at, updated_at,
                             created_by)
                        VALUES (:uuid, :candidate, NULL, :template, 'REJECTION_SCREENING',
                                'REVIEW_FIRST_TEMPLATE', :email, :subject, :body, :status,
                                NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("candidate", candidateUuid)
                .setParameter("template", UUID.randomUUID().toString())
                .setParameter("email", SENTINEL + "@example.invalid")
                .setParameter("subject", SENTINEL + " subject")
                .setParameter("body", SENTINEL + " body")
                .setParameter("status", status)
                .executeUpdate();
    }
}
