package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.PositionRequest;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCircleMember;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentCircleRole;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentDemandRag;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 DoD (API invariants at the service layer): one position per track with
 * track-conditional validation, slug uniqueness, derived {@code practiceCode}
 * (no stored code anywhere), registry mutability (inactive practice blocks
 * creation but never operation), and exact event emission with
 * {@code assertNoPiiInPayload} green on every appended event.
 */
@QuarkusTest
class RecruitmentPositionServiceIntegrationTest {

    @Inject
    RecruitmentPositionService service;

    @Inject
    EntityManager em;

    private final UUID actor = UUID.randomUUID();

    private String activePracticeUuid;
    private String activePracticeCode;
    private String inactivePracticeUuid;
    private final List<String> createdPositions = new ArrayList<>();

    @BeforeEach
    void seedPractices() {
        activePracticeUuid = UUID.randomUUID().toString();
        inactivePracticeUuid = UUID.randomUUID().toString();
        // Registry codes are short; collide-proof the fixture with a random suffix.
        activePracticeCode = "T" + activePracticeUuid.substring(0, 7);
        String inactiveCode = "T" + inactivePracticeUuid.substring(0, 7);
        QuarkusTransaction.requiringNew().run(() -> {
            insertPractice(activePracticeCode, activePracticeUuid, "Test Practice Active", true);
            insertPractice(inactiveCode, inactivePracticeUuid, "Test Practice Inactive", false);
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!createdPositions.isEmpty()) {
                em.createNativeQuery("DELETE FROM recruitment_circle_members WHERE position_uuid IN :uuids")
                        .setParameter("uuids", createdPositions).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_events WHERE position_uuid IN :uuids")
                        .setParameter("uuids", createdPositions).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :uuids")
                        .setParameter("uuids", createdPositions).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM practice WHERE uuid IN (:a, :b)")
                    .setParameter("a", activePracticeUuid)
                    .setParameter("b", inactivePracticeUuid)
                    .executeUpdate();
        });
        createdPositions.clear();
    }

    // ---- Create: one of each track -------------------------------------------

    @Test
    void createPracticeTrack_appliesDefaults_andDerivesPracticeCode() {
        RecruitmentPosition created = create(request(
                "Senior Consultant", RecruitmentHiringTrack.PRACTICE_TEAM,
                activePracticeUuid, null, null, null));

        assertEquals(RecruitmentPositionStatus.OPEN, created.getStatus());
        assertEquals(RecruitmentDemandRag.GREEN, created.getDemandRag());
        assertNull(created.getTeamUuid(), "practice track allows null team (decided at offer)");
        assertEquals(List.of("SCREENING", "INTERVIEW_1", "INTERVIEW_2", "OFFER", "HIRED"),
                created.getStageSet());
        assertEquals(4, created.getScorecardTemplate().size());

        // The DTO carries the DERIVED practiceCode — reload so the @Formula runs.
        RecruitmentPosition reloaded = reload(created.getUuid());
        assertEquals(activePracticeCode, reloaded.getPracticeCode());
        assertEquals(Boolean.TRUE, reloaded.getPracticeActive());

        List<RecruitmentEvent> events = eventsFor(created.getUuid());
        assertEquals(List.of(RecruitmentEventType.POSITION_OPENED),
                events.stream().map(RecruitmentEvent::getEventType).toList());
        assertEquals(RecruitmentEventVisibility.NORMAL, events.get(0).getVisibility());
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    @Test
    void createPartnerTrack_insertsThirdInterview_andBootstrapsCircleOwner() {
        RecruitmentPosition created = create(request(
                "New Partner", RecruitmentHiringTrack.PARTNER, null, null, null, null));

        assertTrue(created.getStageSet().contains("INTERVIEW_3"),
                "partner default stage set inserts INTERVIEW_3");

        // The creator is auto-added as circle OWNER — a partner position must
        // never be born invisible.
        List<RecruitmentCircleMember> members = service.circleMembers(created.getUuid());
        assertEquals(1, members.size());
        assertEquals(actor.toString(), members.get(0).getUserUuid());
        assertEquals(RecruitmentCircleRole.OWNER, members.get(0).getRoleInCircle());

        List<RecruitmentEvent> events = eventsFor(created.getUuid());
        assertEquals(List.of(RecruitmentEventType.POSITION_OPENED, RecruitmentEventType.CIRCLE_MEMBER_ADDED),
                events.stream().map(RecruitmentEvent::getEventType).toList());
        assertTrue(events.stream().allMatch(e -> e.getVisibility() == RecruitmentEventVisibility.CIRCLE),
                "partner-track events carry visibility=CIRCLE");
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    @Test
    void createStaffTrack_requiresHiringOwner() {
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> service.create(request(
                        "Finance Assistant", RecruitmentHiringTrack.STAFF_ROLE,
                        null, null, null, null), actor));
        assertEquals(400, e.getResponse().getStatus());

        RecruitmentPosition created = create(request(
                "Finance Assistant", RecruitmentHiringTrack.STAFF_ROLE,
                null, null, UUID.randomUUID().toString(), null));
        assertNotNull(created.getHiringOwnerUuid());
    }

    @Test
    void createPracticeTrack_requiresPracticeUuid() {
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> service.create(request(
                        "Senior Consultant", RecruitmentHiringTrack.PRACTICE_TEAM,
                        null, null, null, null), actor));
        assertEquals(400, e.getResponse().getStatus());
    }

    @Test
    void create_onInactivePractice_isRejected() {
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> service.create(request(
                        "Senior Consultant", RecruitmentHiringTrack.PRACTICE_TEAM,
                        inactivePracticeUuid, null, null, null), actor));
        assertEquals(400, e.getResponse().getStatus());
    }

    @Test
    void create_onUnknownTeam_isRejected() {
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> service.create(request(
                        "Senior Consultant", RecruitmentHiringTrack.PRACTICE_TEAM,
                        activePracticeUuid, UUID.randomUUID().toString(), null, null), actor));
        assertEquals(400, e.getResponse().getStatus());
    }

    // ---- Slug uniqueness ---------------------------------------------------------

    @Test
    void publicSlug_isUnique_andCaseNormalized() {
        create(request("A", RecruitmentHiringTrack.PRACTICE_TEAM,
                activePracticeUuid, null, null, "senior-consultant"));
        assertThrows(BusinessRuleViolation.class,
                () -> service.create(request("B", RecruitmentHiringTrack.PRACTICE_TEAM,
                        activePracticeUuid, null, null, "senior-consultant"), actor));
    }

    // ---- Registry mutability: graceful degradation --------------------------------

    @Test
    void position_onDeactivatedPractice_staysEditableAndClosable() {
        RecruitmentPosition created = create(request(
                "Survivor", RecruitmentHiringTrack.PRACTICE_TEAM,
                activePracticeUuid, null, null, null));
        deactivatePractice(activePracticeUuid);

        // Edit other fields — the unchanged practice reference is NOT re-validated.
        RecruitmentPosition updated = updateInTx(created.getUuid(), request(
                "Survivor (renamed)", RecruitmentHiringTrack.PRACTICE_TEAM,
                activePracticeUuid, null, null, null));
        assertEquals("Survivor (renamed)", updated.getTitle());

        // The wire shape flags the registry state for the inactive badge.
        assertEquals(Boolean.FALSE, reload(created.getUuid()).getPracticeActive());

        // Close still works end-to-end.
        RecruitmentPosition closed = closeInTx(created.getUuid());
        assertEquals(RecruitmentPositionStatus.CLOSED, closed.getStatus());
        assertNotNull(closed.getClosedAt());

        List<RecruitmentEvent> events = eventsFor(created.getUuid());
        assertEquals(List.of(RecruitmentEventType.POSITION_OPENED,
                        RecruitmentEventType.POSITION_UPDATED,
                        RecruitmentEventType.POSITION_CLOSED),
                events.stream().map(RecruitmentEvent::getEventType).toList());
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    // ---- Update rules ---------------------------------------------------------------

    @Test
    void update_rejectsTrackChange_statusClose_andEditsAfterClose() {
        RecruitmentPosition created = create(request(
                "Rules", RecruitmentHiringTrack.PRACTICE_TEAM,
                activePracticeUuid, null, null, null));

        WebApplicationException trackChange = assertThrows(WebApplicationException.class,
                () -> updateInTx(created.getUuid(), request(
                        "Rules", RecruitmentHiringTrack.STAFF_ROLE,
                        null, null, UUID.randomUUID().toString(), null)));
        assertEquals(400, trackChange.getResponse().getStatus());

        PositionRequest closeViaPut = new PositionRequest(
                "Rules", RecruitmentHiringTrack.PRACTICE_TEAM, activePracticeUuid,
                null, null, null, null, null, null, RecruitmentPositionStatus.CLOSED);
        WebApplicationException closeViaUpdate = assertThrows(WebApplicationException.class,
                () -> updateInTx(created.getUuid(), closeViaPut));
        assertEquals(400, closeViaUpdate.getResponse().getStatus());

        closeInTx(created.getUuid());
        assertThrows(BusinessRuleViolation.class,
                () -> updateInTx(created.getUuid(), request(
                        "Rules again", RecruitmentHiringTrack.PRACTICE_TEAM,
                        activePracticeUuid, null, null, null)));
        assertThrows(BusinessRuleViolation.class, () -> closeInTx(created.getUuid()));
    }

    @Test
    void update_noChanges_appendsNoEvent_andChangesAppendOne() {
        RecruitmentPosition created = create(request(
                "Eventful", RecruitmentHiringTrack.PRACTICE_TEAM,
                activePracticeUuid, null, null, null));
        assertEquals(1, eventsFor(created.getUuid()).size());

        // Identical PUT → no event.
        updateInTx(created.getUuid(), request(
                "Eventful", RecruitmentHiringTrack.PRACTICE_TEAM,
                activePracticeUuid, null, null, null));
        assertEquals(1, eventsFor(created.getUuid()).size());

        // RAG change → exactly one POSITION_UPDATED with the changed field.
        PositionRequest ragChange = new PositionRequest(
                "Eventful", RecruitmentHiringTrack.PRACTICE_TEAM, activePracticeUuid,
                null, null, null, null, null, RecruitmentDemandRag.RED, null);
        updateInTx(created.getUuid(), ragChange);
        List<RecruitmentEvent> events = eventsFor(created.getUuid());
        assertEquals(2, events.size());
        RecruitmentEvent updateEvent = events.get(1);
        assertEquals(RecruitmentEventType.POSITION_UPDATED, updateEvent.getEventType());
        assertTrue(updateEvent.getPayload().contains("\"demand_rag\":\"RED\""));
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    @Test
    void update_invalidStageSetOverride_isRejected() {
        RecruitmentPosition created = create(request(
                "Stages", RecruitmentHiringTrack.PRACTICE_TEAM,
                activePracticeUuid, null, null, null));
        PositionRequest badStages = new PositionRequest(
                "Stages", RecruitmentHiringTrack.PRACTICE_TEAM, activePracticeUuid,
                null, null, null, List.of("OFFER", "SCREENING", "HIRED"), null, null, null);
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> updateInTx(created.getUuid(), badStages));
        assertEquals(400, e.getResponse().getStatus());
    }

    // ---- Circle management -----------------------------------------------------------

    @Test
    void circle_addRemove_emitsEvents_andProtectsLastOwner() {
        RecruitmentPosition partner = create(request(
                "Partner circle", RecruitmentHiringTrack.PARTNER, null, null, null, null));
        String member = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() ->
                service.addCircleMember(reload(partner.getUuid()), member, null, actor));
        assertEquals(2, service.circleMembers(partner.getUuid()).size());

        // Duplicate add → conflict.
        assertThrows(BusinessRuleViolation.class, () ->
                QuarkusTransaction.requiringNew().run(() ->
                        service.addCircleMember(reload(partner.getUuid()), member, null, actor)));

        // The last OWNER can never be removed (the creator here).
        assertThrows(BusinessRuleViolation.class, () ->
                QuarkusTransaction.requiringNew().run(() ->
                        service.removeCircleMember(reload(partner.getUuid()), actor.toString(), actor)));

        QuarkusTransaction.requiringNew().run(() ->
                service.removeCircleMember(reload(partner.getUuid()), member, actor));
        assertEquals(1, service.circleMembers(partner.getUuid()).size());

        List<RecruitmentEvent> events = eventsFor(partner.getUuid());
        assertEquals(List.of(RecruitmentEventType.POSITION_OPENED,
                        RecruitmentEventType.CIRCLE_MEMBER_ADDED,   // creator bootstrap
                        RecruitmentEventType.CIRCLE_MEMBER_ADDED,   // explicit add
                        RecruitmentEventType.CIRCLE_MEMBER_REMOVED),
                events.stream().map(RecruitmentEvent::getEventType).toList());
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    @Test
    void circle_onNonPartnerTrack_isRejected() {
        RecruitmentPosition practice = create(request(
                "No circle here", RecruitmentHiringTrack.PRACTICE_TEAM,
                activePracticeUuid, null, null, null));
        WebApplicationException e = assertThrows(WebApplicationException.class, () ->
                QuarkusTransaction.requiringNew().run(() ->
                        service.addCircleMember(reload(practice.getUuid()),
                                UUID.randomUUID().toString(), null, actor)));
        assertEquals(400, e.getResponse().getStatus());
    }

    // ---- Fixture helpers ----------------------------------------------------------

    private PositionRequest request(String title, RecruitmentHiringTrack track,
                                    String practiceUuid, String teamUuid,
                                    String hiringOwnerUuid, String slug) {
        return new PositionRequest(title, track, practiceUuid, teamUuid, hiringOwnerUuid,
                slug, null, null, null, null);
    }

    private RecruitmentPosition create(PositionRequest request) {
        RecruitmentPosition created = QuarkusTransaction.requiringNew().call(
                () -> service.create(request, actor));
        createdPositions.add(created.getUuid());
        return created;
    }

    private RecruitmentPosition updateInTx(String uuid, PositionRequest request) {
        return QuarkusTransaction.requiringNew().call(
                () -> service.update(reload(uuid), request, actor));
    }

    private RecruitmentPosition closeInTx(String uuid) {
        return QuarkusTransaction.requiringNew().call(
                () -> service.close(reload(uuid), actor));
    }

    private RecruitmentPosition reload(String uuid) {
        em.clear();
        return RecruitmentPosition.findById(uuid);
    }

    private List<RecruitmentEvent> eventsFor(String positionUuid) {
        em.clear();
        return RecruitmentEvent.list("positionUuid = ?1 order by seq", positionUuid);
    }

    private void insertPractice(String code, String uuid, String name, boolean active) {
        em.createNativeQuery("""
                        INSERT INTO practice (code, uuid, name, active, sort_order,
                                              created_at, updated_at, created_by)
                        VALUES (:code, :uuid, :name, :active, 999, NOW(), NOW(), 'test')
                        """)
                .setParameter("code", code)
                .setParameter("uuid", uuid)
                .setParameter("name", name)
                .setParameter("active", active)
                .executeUpdate();
    }

    private void deactivatePractice(String uuid) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE practice SET active = 0 WHERE uuid = :uuid")
                        .setParameter("uuid", uuid)
                        .executeUpdate());
    }
}
