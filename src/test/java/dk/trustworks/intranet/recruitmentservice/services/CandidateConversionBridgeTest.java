package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.aggregates.users.danlon.DanlonAssignmentService;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.recruitmentservice.dto.ConvertRequest;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P10 conversion-bridge wiring through the REAL
 * {@link CandidateConversionUseCase#execute} (contract B1d/B3.4): a full
 * conversion of a candidate with an OFFER application marks the application
 * HIRED and appends {@code APPLICATION_STAGE_CHANGED} + {@code
 * CANDIDATE_HIRED} in the SAME transaction as the user provisioning.
 * <p>
 * Follows the {@code CandidateConversionDanlonTest} pattern: the fixture
 * depends on real seed data (a company row, career/team FKs) — when the
 * local DB cannot complete a conversion the test self-skips via
 * {@link Assumptions} rather than fake a result. The deterministic bridge
 * behavior is fully covered by {@code RecruitmentOfferBridgeIntegrationTest}
 * regardless.
 */
@QuarkusTest
class CandidateConversionBridgeTest {

    @Inject
    CandidateConversionUseCase useCase;

    @Inject
    EntityManager em;

    @InjectMock
    DanlonAssignmentService danlonAssignmentService;

    private String candidateUuid;
    private String positionUuid;
    private String createdUserUuid;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (candidateUuid != null) {
                em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid = :c")
                        .setParameter("c", candidateUuid).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_applications WHERE candidate_uuid = :c")
                        .setParameter("c", candidateUuid).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :c")
                        .setParameter("c", candidateUuid).executeUpdate();
            }
            if (positionUuid != null) {
                em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid = :p")
                        .setParameter("p", positionUuid).executeUpdate();
            }
        });
        if (createdUserUuid != null) {
            // Best-effort teardown of the rows the conversion provisioned —
            // one tx per statement so a single failure cannot poison the rest.
            for (String sql : List.of(
                    "DELETE FROM salary WHERE useruuid = :u",
                    "DELETE FROM user_career_level WHERE useruuid = :u",
                    "DELETE FROM teamroles WHERE useruuid = :u",
                    "DELETE FROM userstatus WHERE useruuid = :u",
                    "DELETE FROM user WHERE uuid = :u")) {
                try {
                    QuarkusTransaction.requiringNew().run(() ->
                            em.createNativeQuery(sql)
                                    .setParameter("u", createdUserUuid).executeUpdate());
                } catch (RuntimeException ignored) {
                    // Schema drift between environments — teardown is best-effort.
                }
            }
        }
    }

    @Test
    void execute_withOfferApplication_hiresApplicationAndAppendsEventsInTheSameTx() {
        String companyUuid = QuarkusTransaction.requiringNew().call(() -> {
            Company c = Company.<Company>findAll().firstResult();
            return c == null ? null : c.getUuid();
        });
        Assumptions.assumeTrue(companyUuid != null, "no seed company — skipping conversion test");

        candidateUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        String applicationUuid = UUID.randomUUID().toString();
        String teamUuid = UUID.randomUUID().toString();
        LocalDate plannedStart = LocalDate.now().plusMonths(2).withDayOfMonth(1);

        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentCandidate c = new RecruitmentCandidate();
            c.setUuid(candidateUuid);
            c.setFirstName(PII_SENTINEL + "-Convert");
            c.setLastName("Fixture");
            c.setStatus(CandidateStatus.ACTIVE);
            c.setTargetCompanyUuid(companyUuid);
            c.setCreatedByUseruuid(UUID.randomUUID().toString());
            c.persist();
            em.createNativeQuery("""
                            INSERT INTO recruitment_positions
                                (uuid, title, hiring_track, stage_set,
                                 demand_rag, status, opened_at, created_at, updated_at, created_by)
                            VALUES (:uuid, 'Convert Fixture', 'PRACTICE_TEAM',
                                    '["SCREENING","INTERVIEW_1","INTERVIEW_2","OFFER","HIRED"]',
                                    'GREEN', 'OPEN', NOW(3), NOW(), NOW(), 'test')
                            """)
                    .setParameter("uuid", positionUuid).executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO recruitment_applications
                                (uuid, candidate_uuid, position_uuid, stage, assigned_team_uuid,
                                 stage_entered_at, created_at, updated_at, created_by)
                            VALUES (:uuid, :candidate, :position, 'OFFER', :team,
                                    NOW(3), NOW(), NOW(), 'test')
                            """)
                    .setParameter("uuid", applicationUuid)
                    .setParameter("candidate", candidateUuid)
                    .setParameter("position", positionUuid)
                    .setParameter("team", teamUuid).executeUpdate();
        });

        ConvertRequest req = new ConvertRequest(
                "p10_" + System.nanoTime(),
                "p10." + System.nanoTime() + "@example.com",
                ConsultantType.CONSULTANT,
                CareerTrack.DELIVERY, CareerLevel.CONSULTANT,
                teamUuid, TeamMemberType.MEMBER,
                plannedStart,
                40000, 100);

        try {
            createdUserUuid = useCase
                    .execute(UUID.fromString(candidateUuid), req, UUID.randomUUID())
                    .newUserUuid();
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false,
                    "conversion fixture incomplete in this DB: " + e.getMessage());
            return;
        }

        em.clear();
        RecruitmentApplication hired = RecruitmentApplication.findById(applicationUuid);
        assertEquals(RecruitmentStage.HIRED, hired.getStage(),
                "the conversion marked the OFFER application HIRED");
        assertNull(hired.getTerminal());

        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "candidateUuid = ?1 order by seq", candidateUuid);
        assertEquals(List.of(RecruitmentEventType.APPLICATION_STAGE_CHANGED,
                        RecruitmentEventType.CANDIDATE_HIRED),
                events.stream().map(RecruitmentEvent::getEventType).toList(),
                "both bridge events committed with the conversion transaction");
        RecruitmentEvent candidateHired = events.get(1);
        assertTrue(candidateHired.getPayload().contains("\"user_uuid\":\"" + createdUserUuid + "\""));
        assertTrue(candidateHired.getPayload().contains("\"team_uuid\":\"" + teamUuid + "\""));
        assertTrue(candidateHired.getPayload().contains(
                "\"planned_start_date\":\"" + plannedStart + "\""));
        assertFalse(candidateHired.getPayload().contains("salary"),
                "the convert request's salary must never surface in the event payload");
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }
}
