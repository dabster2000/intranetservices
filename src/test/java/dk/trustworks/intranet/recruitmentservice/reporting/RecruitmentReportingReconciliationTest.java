package dk.trustworks.intranet.recruitmentservice.reporting;

import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAnonymizerService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentS3StorageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventTestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P20 DoD locks, driven against the local DB:
 * <ol>
 *   <li><b>Reconciliation:</b> rebuild-from-stream equals the incrementally
 *       maintained projection on a rich fixture (both paths fold the same
 *       immutable stream, so every cell must match).</li>
 *   <li><b>Anonymization-proof:</b> anonymizing a hired-adjacent fixture
 *       candidate — with the REAL {@code RecruitmentAnonymizerService} —
 *       changes no non-GDPR report number, on the live projection AND on a
 *       full rebuild from the now-anonymized stream.</li>
 *   <li>Fixture-level spot checks: time-in-stage sums, the terminal
 *       family, the referrer resolved for a triage via the stream, and the
 *       web/slack origin split.</li>
 * </ol>
 */
@QuarkusTest
class RecruitmentReportingReconciliationTest {

    @Inject
    RecruitmentReportingProjector projector;

    @Inject
    RecruitmentAnonymizerService anonymizer;

    @Inject
    EntityManager em;

    @InjectMock
    RecruitmentS3StorageService storageService;

    private String positionUuid;
    private String candidateUuid;    // hired-adjacent: rejected at OFFER
    private String hiredCandidateUuid;
    private String applicationUuid;
    private String hiredApplicationUuid;
    private String referralUuid;
    private final String referrer = UUID.randomUUID().toString();
    private final String interviewer = UUID.randomUUID().toString();
    private final String recruiter = UUID.randomUUID().toString();

    /** Months well in the past — nothing here fights the catch-up horizon (0 in tests anyway). */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 3, 10, 9, 0);

    @BeforeEach
    void seed() {
        when(storageService.deleteAllCandidateFiles(any(UUID.class))).thenReturn(0);
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        hiredCandidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        hiredApplicationUuid = UUID.randomUUID().toString();
        referralUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            INSERT INTO recruitment_positions
                                (uuid, title, hiring_track, stage_set, demand_rag, status,
                                 opened_at, created_at, updated_at, created_by)
                            VALUES (:uuid, 'P20 Reconciliation Position', 'STAFF_ROLE',
                                    '["SCREENING","INTERVIEW_1","OFFER","HIRED"]', 'GREEN', 'OPEN',
                                    NOW(), NOW(), NOW(), 'test')
                            """)
                    .setParameter("uuid", positionUuid).executeUpdate();
            insertCandidate(candidateUuid, "POOLED", referrer);
            insertCandidate(hiredCandidateUuid, "HIRED", null);

            // --- the hired-adjacent candidate's journey -------------------
            insertEvent("REFERRAL_SUBMITTED", null, null, null, referrer, "USER",
                    T0.minusDays(2),
                    "{\"referral_uuid\":\"" + referralUuid + "\",\"relation\":\"FORMER_COLLEAGUE\",\"origin\":\"web\"}");
            insertEvent("REFERRAL_TRIAGED", candidateUuid, null, null, recruiter, "USER",
                    T0.minusDays(1),
                    "{\"referral_uuid\":\"" + referralUuid + "\",\"outcome\":\"CANDIDATE_CREATED\",\"origin\":\"slack\"}");
            insertEvent("CANDIDATE_CREATED", candidateUuid, null, null, recruiter, "USER",
                    T0.minusDays(1).plusHours(1),
                    "{\"source\":\"REFERRAL\",\"referred_by_user_uuid\":\"" + referrer + "\"}");
            insertEvent("APPLICATION_CREATED", candidateUuid, applicationUuid, positionUuid, recruiter, "USER",
                    T0,
                    "{\"position_title\":\"P20 Reconciliation Position\",\"hiring_track\":\"STAFF_ROLE\","
                    + "\"initial_stage\":\"SCREENING\",\"origin\":\"manual\"}");
            insertEvent("APPLICATION_STAGE_CHANGED", candidateUuid, applicationUuid, positionUuid, recruiter, "USER",
                    T0.plusDays(3),
                    "{\"from\":\"SCREENING\",\"to\":\"INTERVIEW_1\",\"direction\":\"FORWARD\",\"skipped_stages\":[]}");
            insertEvent("SCORECARD_SUBMITTED", candidateUuid, applicationUuid, positionUuid, interviewer, "USER",
                    T0.plusDays(5),
                    "{\"scorecard_uuid\":\"" + UUID.randomUUID() + "\",\"origin\":\"slack\"}");
            insertEvent("APPLICATION_STAGE_CHANGED", candidateUuid, applicationUuid, positionUuid, recruiter, "USER",
                    T0.plusDays(7),
                    "{\"from\":\"INTERVIEW_1\",\"to\":\"OFFER\",\"direction\":\"FORWARD\",\"skipped_stages\":[]}");
            insertEvent("APPLICATION_REJECTED", candidateUuid, applicationUuid, positionUuid, recruiter, "USER",
                    T0.plusDays(10),
                    "{\"reason_code\":\"COMP_MISMATCH\",\"from_stage\":\"OFFER\"}");
            insertEvent("CONSENT_GRANTED", candidateUuid, null, null, null, "CANDIDATE",
                    T0.plusDays(11),
                    "{\"kind\":\"TALENT_POOL_RETENTION\",\"origin\":\"consent_page\"}");

            // --- the hired candidate (untouched by anonymization) ---------
            insertEvent("CANDIDATE_CREATED", hiredCandidateUuid, null, null, recruiter, "USER",
                    T0.plusMonths(1),
                    "{\"source\":\"WEBSITE\"}");
            insertEvent("APPLICATION_CREATED", hiredCandidateUuid, hiredApplicationUuid, positionUuid, recruiter, "USER",
                    T0.plusMonths(1).plusDays(1),
                    "{\"hiring_track\":\"STAFF_ROLE\",\"initial_stage\":\"SCREENING\",\"origin\":\"public_form\"}");
            insertEvent("APPLICATION_STAGE_CHANGED", hiredCandidateUuid, hiredApplicationUuid, positionUuid, recruiter, "USER",
                    T0.plusMonths(1).plusDays(4),
                    "{\"from\":\"SCREENING\",\"to\":\"OFFER\",\"direction\":\"FORWARD\",\"skipped_stages\":[\"INTERVIEW_1\"]}");
            insertEvent("CANDIDATE_HIRED", hiredCandidateUuid, hiredApplicationUuid, positionUuid, null, "SYSTEM",
                    T0.plusMonths(1).plusDays(9),
                    "{\"user_uuid\":\"" + UUID.randomUUID() + "\"}");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String candidate : List.of(candidateUuid, hiredCandidateUuid)) {
                em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid = :c")
                        .setParameter("c", candidate).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :c")
                        .setParameter("c", candidate).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM recruitment_events WHERE actor_uuid = :a")
                    .setParameter("a", referrer).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid = :p")
                    .setParameter("p", positionUuid).executeUpdate();
        });
        // Leave the projection consistent with the cleaned stream for whoever runs next.
        projector.rebuild();
    }

    // ------------------------------------------------------------------

    @Test
    void rebuildEqualsIncrementallyMaintainedState_andSurvivesAnonymization() {
        // --- Phase A: incremental (live-path) projection of the stream ----
        resetProjection();
        for (Long seq : allSeqs()) {
            projector.deliverLive(seq);
        }
        List<String> incremental = factSnapshot();
        assertTrue(incremental.stream().anyMatch(r -> r.contains("STAGE_MOVED")),
                "fixture must have produced stage-move facts");

        // --- Phase B: full rebuild must reproduce it cell for cell --------
        RecruitmentReportingProjector.RebuildSummary summary = projector.rebuild();
        assertTrue(summary.eventsProjected() > 0, "rebuild must actually replay the stream");
        assertEquals(incremental, factSnapshot(),
                "rebuild-from-stream must equal the incrementally maintained projection");

        // --- Fixture spot checks ------------------------------------------
        // Time in SCREENING: 3 days (created T0 → moved T0+3d)
        assertEquals("3.00", scalar("""
                SELECT CAST(sum_days AS CHAR) FROM recruitment_fact_monthly
                WHERE fact = 'STAGE_MOVED' AND position_uuid = :pos
                  AND stage_from = 'SCREENING' AND stage_to = 'INTERVIEW_1'
                """, "pos", positionUuid));
        // Terminal at OFFER with the coded reason, 3 days after entering OFFER
        assertEquals("1", scalar("""
                SELECT CAST(SUM(cnt) AS CHAR) FROM recruitment_fact_monthly
                WHERE fact = 'TERMINAL' AND position_uuid = :pos
                  AND outcome = 'REJECTED' AND stage_from = 'OFFER' AND detail = 'COMP_MISMATCH'
                """, "pos", positionUuid));
        // The triage resolved its referrer from the stream (the actor of REFERRAL_SUBMITTED)
        assertEquals("1", scalar("""
                SELECT CAST(SUM(cnt) AS CHAR) FROM recruitment_fact_monthly
                WHERE fact = 'REFERRAL_TRIAGED' AND person_uuid = :ref
                  AND outcome = 'CANDIDATE_CREATED' AND detail = 'slack'
                """, "ref", referrer));
        // Scorecard counted per interviewer with its Slack origin
        assertEquals("1", scalar("""
                SELECT CAST(SUM(cnt) AS CHAR) FROM recruitment_fact_monthly
                WHERE fact = 'SCORECARD_SUBMITTED' AND person_uuid = :i AND outcome = 'slack'
                """, "i", interviewer));
        // The hire carries the '' referrer (WEBSITE candidate was not referred)
        assertEquals("1", scalar("""
                SELECT CAST(SUM(cnt) AS CHAR) FROM recruitment_fact_monthly
                WHERE fact = 'HIRED' AND position_uuid = :pos AND person_uuid = ''
                """, "pos", positionUuid));

        // --- Phase C: anonymize the hired-adjacent candidate --------------
        List<String> before = nonGdprFactSnapshot();
        anonymizer.anonymize(candidateUuid, RecruitmentAnonymizerService.Mode.ON_REQUEST, recruiter);

        // The live path folds the CANDIDATE_ANONYMIZED event asynchronously.
        assertTrue(awaitTrue(() -> !scalar("""
                        SELECT CAST(COUNT(*) AS CHAR) FROM recruitment_fact_monthly
                        WHERE fact = 'ANONYMIZED' AND outcome = 'ON_REQUEST'
                        """, null, null).equals("0"), 10_000),
                "the ANONYMIZED counter must appear after the anonymization event");

        assertEquals(before, nonGdprFactSnapshot(),
                "anonymization must not change any non-GDPR report number (live projection)");

        // --- Phase D: rebuild from the ANONYMIZED stream — still identical
        projector.rebuild();
        assertEquals(before, nonGdprFactSnapshot(),
                "a rebuild from the anonymized stream must reproduce the same report numbers");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void insertCandidate(String uuid, String status, String referredBy) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_candidates
                            (uuid, first_name, last_name, email, status, source, referred_by_user_uuid,
                             created_by_useruuid, created_at, updated_at)
                        VALUES (:uuid, 'P20', 'Fixture', :email, :status, :source, :referredBy,
                                :actor, NOW(), NOW())
                        """)
                .setParameter("uuid", uuid)
                .setParameter("email", "p20." + uuid.substring(0, 8) + "@example.invalid")
                .setParameter("status", status)
                .setParameter("source", referredBy != null ? "REFERRAL" : "WEBSITE")
                .setParameter("referredBy", referredBy)
                .setParameter("actor", recruiter)
                .executeUpdate();
    }

    private void insertEvent(String type, String candidate, String application, String position,
                             String actor, String actorType, LocalDateTime occurredAt, String payload) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_events
                            (event_id, event_type, candidate_uuid, application_uuid, position_uuid,
                             actor_uuid, actor_type, occurred_at, visibility, payload, pii, pii_state)
                        VALUES (:id, :type, :candidate, :application, :position,
                                :actor, :actorType, :occurredAt, 'NORMAL', :payload, NULL, 'NONE')
                        """)
                .setParameter("id", UUID.randomUUID().toString())
                .setParameter("type", type)
                .setParameter("candidate", candidate)
                .setParameter("application", application)
                .setParameter("position", position)
                .setParameter("actor", actor)
                .setParameter("actorType", actorType)
                .setParameter("occurredAt", occurredAt)
                .setParameter("payload", payload)
                .executeUpdate();
    }

    private void resetProjection() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_reactor_deliveries WHERE reactor_name = 'reporting-projector'")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_fact_monthly").executeUpdate();
            em.createNativeQuery("INSERT INTO recruitment_reactor_offsets (reactor_name, last_processed_seq) " +
                            "VALUES ('reporting-projector', 0) ON DUPLICATE KEY UPDATE last_processed_seq = 0")
                    .executeUpdate();
        });
    }

    @SuppressWarnings("unchecked")
    private List<Long> allSeqs() {
        return QuarkusTransaction.requiringNew().call(() ->
                ((List<Object>) em.createNativeQuery("SELECT seq FROM recruitment_events ORDER BY seq")
                        .getResultList()).stream().map(o -> ((Number) o).longValue()).toList());
    }

    private List<String> factSnapshot() {
        return snapshot("SELECT CONCAT_WS('|', month, fact, position_uuid, practice_uuid, hiring_track, source, "
                + "stage_from, stage_to, outcome, detail, person_uuid, cnt, sum_days) "
                + "FROM recruitment_fact_monthly ORDER BY 1");
    }

    private List<String> nonGdprFactSnapshot() {
        return snapshot("SELECT CONCAT_WS('|', month, fact, position_uuid, practice_uuid, hiring_track, source, "
                + "stage_from, stage_to, outcome, detail, person_uuid, cnt, sum_days) "
                + "FROM recruitment_fact_monthly "
                + "WHERE fact NOT IN ('ANONYMIZED', 'ART14_NOTICE_SENT', 'CONSENT_GRANTED', 'CONSENT_WITHDRAWN', "
                + "'CONSENT_EXPIRED', 'DSAR_RECEIVED', 'DSAR_EXPORTED') ORDER BY 1");
    }

    @SuppressWarnings("unchecked")
    private List<String> snapshot(String sql) {
        return QuarkusTransaction.requiringNew().call(() ->
                ((List<Object>) em.createNativeQuery(sql).getResultList()).stream()
                        .map(Object::toString).toList());
    }

    private String scalar(String sql, String param, String value) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var query = em.createNativeQuery(sql);
            if (param != null) {
                query.setParameter(param, value);
            }
            Object result = query.getSingleResult();
            return result == null ? "0" : result.toString();
        });
    }
}
