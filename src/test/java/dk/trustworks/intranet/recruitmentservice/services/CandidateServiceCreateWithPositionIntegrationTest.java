package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2/B3 at the service layer: the atomic create-with-position.
 *
 * <ul>
 *   <li><b>Atomicity</b> — a refused attach must leave <em>nothing</em>
 *       behind. This is the whole reason the composition lives inside
 *       {@code CandidateService} and not in the resource: both halves are
 *       {@code @Transactional} REQUIRED, so the inner attach joins the outer
 *       transaction and a rollback takes the candidate row and its
 *       {@code CANDIDATE_CREATED} event with it. In the resource — which has
 *       no transaction — the candidate would already be committed.</li>
 *   <li><b>Event stream equivalence</b> — the atomic path must produce
 *       exactly {@code CANDIDATE_CREATED} then {@code APPLICATION_CREATED},
 *       the same pair, in the same order, as the two-step
 *       create-then-attach flow every reactor and projection was built
 *       against.</li>
 *   <li><b>Partner stamping (B3)</b> — on a PARTNER position the
 *       {@code CANDIDATE_CREATED} event carries CIRCLE visibility
 *       <em>and</em> the position uuid. Both, because the CIRCLE readers
 *       resolve the circle from the event's position: a CIRCLE event with a
 *       null position fails closed for everyone, including the recruiter who
 *       just created the candidate.</li>
 * </ul>
 *
 * <h3>How to actually run this</h3>
 * It is a {@code @QuarkusTest}, so the CI deploy gate
 * ({@code -DexcludedGroups=io.quarkus.test.junit.QuarkusTest}) skips it and
 * it proves nothing until someone points it at a database. Verified working
 * 2026-08-19 against a throwaway clone of the local Docker DB:
 * <pre>
 * ./mvnw test -Dtest='CandidateServiceCreateWithPositionIntegrationTest' -Dsurefire.forkCount=1 -Dsurefire.maxHeap=2g \
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
class CandidateServiceCreateWithPositionIntegrationTest {

    @Inject
    CandidateService candidateService;

    @Inject
    EntityManager em;

    private final UUID actor = UUID.randomUUID();
    private final List<String> candidateUuids = new ArrayList<>();
    private final List<String> positionUuids = new ArrayList<>();

    private String practiceUuid;
    private String openPosition;
    private String closedPosition;
    private String partnerPosition;

    @BeforeEach
    void seedFixtures() {
        practiceUuid = UUID.randomUUID().toString();
        openPosition = UUID.randomUUID().toString();
        closedPosition = UUID.randomUUID().toString();
        partnerPosition = UUID.randomUUID().toString();
        positionUuids.addAll(List.of(openPosition, closedPosition, partnerPosition));

        QuarkusTransaction.requiringNew().run(() -> {
            insertPractice(practiceUuid);
            insertPosition(openPosition, "Atomic consultant", "PRACTICE_TEAM", practiceUuid, "OPEN");
            insertPosition(closedPosition, "Filled req", "PRACTICE_TEAM", practiceUuid, "CLOSED");
            insertPosition(partnerPosition, "Partner hire", "PARTNER", null, "OPEN");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!candidateUuids.isEmpty()) {
                em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_consents WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_applications WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM recruitment_events WHERE position_uuid IN :p")
                    .setParameter("p", positionUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", positionUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM practice WHERE uuid = :pr")
                    .setParameter("pr", practiceUuid).executeUpdate();
        });
        candidateUuids.clear();
        positionUuids.clear();
    }

    // ---- B2: atomicity ----------------------------------------------------------

    @Test
    void aRefusedAttach_rollsTheCandidateCreationBack() {
        // A CLOSED position is refused by the shared §4.2 invariants inside
        // RecruitmentApplicationService.createCore — i.e. AFTER the candidate
        // row and its event have been written in this same transaction.
        String email = "atomic-rollback-" + UUID.randomUUID() + "@example.com";
        RecruitmentPosition closed = position(closedPosition);

        assertThrows(BusinessRuleViolation.class,
                () -> candidateService.createCandidate(request(email), actor, closed),
                "attaching to a CLOSED position must be refused");

        assertEquals(0L, RecruitmentCandidate.count("email", email),
                "the candidate must NOT survive a refused attach — that is the stranded row "
                        + "the in-service composition exists to prevent");
        assertEquals(0L, countEventsForEmailCandidate(email),
                "and no CANDIDATE_CREATED event may be left behind either");
    }

    // ---- B2: event stream equivalence -------------------------------------------

    @Test
    void theAtomicPath_emitsCandidateCreatedThenApplicationCreated() {
        CandidateResponse created = create(request(uniqueEmail()), position(openPosition));

        List<RecruitmentEvent> events = eventsOf(created.uuid());
        assertEquals(2, events.size(),
                "exactly the two events of the two-step flow, no more: " + typesOf(events));
        assertEquals(RecruitmentEventType.CANDIDATE_CREATED, events.get(0).getEventType());
        assertEquals(RecruitmentEventType.APPLICATION_CREATED, events.get(1).getEventType());
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    @Test
    void theAtomicPath_reportsTheCreatedApplication() {
        CandidateResponse created = create(request(uniqueEmail()), position(openPosition));

        assertNotNull(created.applicationUuid(),
                "the response must carry the new application so the caller can navigate to "
                        + "the pipeline card without a second round trip");
        RecruitmentApplication application =
                RecruitmentApplication.findById(created.applicationUuid());
        assertNotNull(application, "the reported application uuid must resolve");
        assertEquals(created.uuid(), application.getCandidateUuid());
        assertEquals(openPosition, application.getPositionUuid());
    }

    @Test
    void thePositionlessCreate_isUnchanged() {
        CandidateResponse created = create(request(uniqueEmail()), null);

        assertNull(created.applicationUuid(), "no position supplied, no application reported");
        List<RecruitmentEvent> events = eventsOf(created.uuid());
        assertEquals(1, events.size(), "still exactly one event: " + typesOf(events));
        assertEquals(RecruitmentEventType.CANDIDATE_CREATED, events.get(0).getEventType());
        assertEquals(RecruitmentEventVisibility.NORMAL, events.get(0).getVisibility());
        assertNull(events.get(0).getPositionUuid());
    }

    // ---- B3: partner stamping ---------------------------------------------------

    @Test
    void creatingOnAPartnerPosition_stampsCircleAndThePosition() {
        CandidateResponse created = create(request(uniqueEmail()), position(partnerPosition));

        RecruitmentEvent candidateCreated = eventsOf(created.uuid()).get(0);
        assertEquals(RecruitmentEventType.CANDIDATE_CREATED, candidateCreated.getEventType());
        assertEquals(RecruitmentEventVisibility.CIRCLE, candidateCreated.getVisibility(),
                "a candidate created straight onto a partner req must not be broadcast");
        assertEquals(partnerPosition, candidateCreated.getPositionUuid(),
                "the position stamp is not optional: the CIRCLE readers resolve the circle "
                        + "FROM it, so a CIRCLE event with a null position is invisible to "
                        + "everyone — including the recruiter who just created the candidate");
    }

    @Test
    void creatingOnANonPartnerPosition_staysNormalVisibility() {
        CandidateResponse created = create(request(uniqueEmail()), position(openPosition));

        RecruitmentEvent candidateCreated = eventsOf(created.uuid()).get(0);
        assertEquals(RecruitmentEventVisibility.NORMAL, candidateCreated.getVisibility());
        assertTrue(candidateCreated.getPayload().contains(openPosition),
                "the ordinary track records the position as a structural fact, not a secret");
    }

    // ---- Helpers ----------------------------------------------------------------

    private CandidateResponse create(CandidateRequest request, RecruitmentPosition position) {
        CandidateResponse created = candidateService.createCandidate(request, actor, position);
        candidateUuids.add(created.uuid());
        return created;
    }

    private static String uniqueEmail() {
        return "atomic-" + UUID.randomUUID() + "@example.com";
    }

    private static CandidateRequest request(String email) {
        return new CandidateRequest(
                PII_SENTINEL + "-Atomic", PII_SENTINEL + "-Fixture", email,
                null, null, null, null, null, null,
                CandidateSource.OTHER,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null,
                // positionUuid on the DTO is the RESOURCE's input; the service
                // overload takes the resolved entity, so it stays null here.
                null);
    }

    private static RecruitmentPosition position(String uuid) {
        return RecruitmentPosition.findById(uuid);
    }

    private static List<RecruitmentEvent> eventsOf(String candidateUuid) {
        return RecruitmentEvent.list("candidateUuid = ?1 order by seq", candidateUuid);
    }

    private static List<RecruitmentEventType> typesOf(List<RecruitmentEvent> events) {
        return events.stream().map(RecruitmentEvent::getEventType).toList();
    }

    private long countEventsForEmailCandidate(String email) {
        return ((Number) em.createNativeQuery("""
                        SELECT COUNT(*) FROM recruitment_events e
                        WHERE e.candidate_uuid IN (
                            SELECT c.uuid FROM recruitment_candidates c WHERE c.email = :email)
                        """)
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private void insertPractice(String uuid) {
        em.createNativeQuery("""
                        INSERT INTO practice (code, uuid, name, active, sort_order,
                                              created_at, updated_at, created_by)
                        VALUES (:code, :uuid, 'Atomic Fixture', 1, 999, NOW(), NOW(), 'test')
                        """)
                .setParameter("code", "C" + uuid.substring(0, 7))
                .setParameter("uuid", uuid)
                .executeUpdate();
    }

    private void insertPosition(String uuid, String title, String track,
                                String practiceUuid, String status) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, practice_uuid, stage_set,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :title, :track, :practice,
                                '["SCREENING","INTERVIEW_1","INTERVIEW_2","OFFER","HIRED"]',
                                'GREEN', :status, NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("title", title)
                .setParameter("track", track)
                .setParameter("practice", practiceUuid)
                .setParameter("status", status)
                .executeUpdate();
    }
}
