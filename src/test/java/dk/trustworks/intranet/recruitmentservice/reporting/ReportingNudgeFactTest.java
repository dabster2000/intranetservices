package dk.trustworks.intranet.recruitmentservice.reporting;

import dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P24 §scope — the P20 carry-over executed: the three P17 SLA nudge event
 * types project into one {@code NUDGE_SENT} fact keyed by nudge type
 * (the weekly digest's "scorecard SLA stats" input). Historical nudges
 * enter via the standard rebuild — this test drives the live/catch-up
 * path against the real chassis and asserts per-fixture-position rows
 * only (the shared local DB carries real facts).
 */
@QuarkusTest
class ReportingNudgeFactTest {

    @Inject
    EntityManager em;

    @Inject
    RecruitmentReportingProjector projector;

    private String practiceUuid;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private String actorUuid;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        actorUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, actorUuid, "Nadia", "Nudge");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Nudge Fact Position",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertCandidate(em, candidateUuid, "Nudge", "Fixture",
                    "ACTIVE", null, null, "test");
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "INTERVIEW_1");
        });
        // Drain the backlog so this test's sweep only reflects its own events.
        projector.catchUp();
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_fact_monthly WHERE position_uuid = :p")
                    .setParameter("p", positionUuid).executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid),
                    List.of(actorUuid), practiceUuid);
        });
        projector.catchUp();
    }

    @Test
    void theThreeSlaNudgeTypes_projectAsOneFactKeyedByType() {
        QuarkusTransaction.requiringNew().run(() -> {
            insertNudge("SCORECARD_NUDGED");
            insertNudge("SCORECARD_NUDGED");
            insertNudge("CANDIDATE_IDLE_NUDGED");
            insertNudge("DEBRIEF_STALLED_NUDGED");
        });
        projector.catchUp();

        assertEquals(2, nudgeCount("SCORECARD_NUDGED"));
        assertEquals(1, nudgeCount("CANDIDATE_IDLE_NUDGED"));
        assertEquals(1, nudgeCount("DEBRIEF_STALLED_NUDGED"));
        // Position-anchored dims resolve (practice from the position row).
        long withPractice = QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery(
                                "SELECT COALESCE(SUM(cnt), 0) FROM recruitment_fact_monthly "
                                + "WHERE fact = 'NUDGE_SENT' AND position_uuid = :p "
                                + "AND practice_uuid = :practice")
                        .setParameter("p", positionUuid)
                        .setParameter("practice", practiceUuid)
                        .getSingleResult()).longValue());
        assertEquals(4, withPractice);
    }

    private void insertNudge(String eventType) {
        P8ProfileFixtures.insertEvent(em, eventType, candidateUuid, applicationUuid,
                positionUuid, "SCHEDULER", null, "NORMAL",
                "{\"nudged_user_uuids\":[\"" + actorUuid + "\"]}", null);
    }

    private long nudgeCount(String detail) {
        return QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery(
                                "SELECT COALESCE(SUM(cnt), 0) FROM recruitment_fact_monthly "
                                + "WHERE fact = 'NUDGE_SENT' AND position_uuid = :p AND detail = :d")
                        .setParameter("p", positionUuid)
                        .setParameter("d", detail)
                        .getSingleResult()).longValue());
    }
}
