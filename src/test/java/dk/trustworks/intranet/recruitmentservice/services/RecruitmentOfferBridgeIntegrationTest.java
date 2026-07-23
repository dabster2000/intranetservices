package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentActorType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P10 offer bridge (contract B1a/B1b/B1c/B1d) at the service layer:
 * <ul>
 *   <li>every entry into OFFER — single step, fast-track skip, re-entry —
 *       appends {@code OFFER_OPENED} with the dossier link facts;</li>
 *   <li>the signature-send gate blocks ONLY the practice-track OFFER
 *       application without a team (409 {@code TEAM_NOT_ASSIGNED} with
 *       message + guidance) and passes every legacy/edge shape;</li>
 *   <li>{@code recordSigningCompletedIfNew} appends once, durably;</li>
 *   <li>the conversion bridge marks OFFER applications HIRED and appends
 *       {@code APPLICATION_STAGE_CHANGED} + {@code CANDIDATE_HIRED} in the
 *       caller's transaction (rollback leaves nothing);</li>
 *   <li>{@code assertNoPiiInPayload} green on every appended event.</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentOfferBridgeIntegrationTest {

    @Inject
    RecruitmentOfferBridge bridge;

    @Inject
    RecruitmentApplicationService applicationService;

    @Inject
    EntityManager em;

    private final UUID actor = UUID.randomUUID();

    private String practicePositionUuid; // PRACTICE_TEAM, full stage set
    private String partnerPositionUuid;  // PARTNER, full set + INTERVIEW_3

    private final List<String> candidateUuids = new ArrayList<>();
    private final List<String> positionUuids = new ArrayList<>();

    @BeforeEach
    void seedFixtures() {
        practicePositionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        positionUuids.add(practicePositionUuid);
        positionUuids.add(partnerPositionUuid);

        QuarkusTransaction.requiringNew().run(() -> {
            insertPosition(practicePositionUuid, "Bridge Consultant", "PRACTICE_TEAM",
                    "[\"SCREENING\",\"INTERVIEW_1\",\"INTERVIEW_2\",\"OFFER\",\"HIRED\"]");
            insertPosition(partnerPositionUuid, "Bridge Partner hire", "PARTNER",
                    "[\"SCREENING\",\"INTERVIEW_1\",\"INTERVIEW_2\",\"INTERVIEW_3\",\"OFFER\",\"HIRED\"]");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!candidateUuids.isEmpty()) {
                em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery(
                                "DELETE FROM recruitment_signing_completed_cases WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_applications WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery("DELETE FROM candidate_dossiers WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM recruitment_events WHERE position_uuid IN :p")
                    .setParameter("p", positionUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", positionUuids).executeUpdate();
        });
        candidateUuids.clear();
        positionUuids.clear();
    }

    // ---- B1a: OFFER entry → OFFER_OPENED --------------------------------------------

    @Test
    void offerEntry_withoutDossier_emitsOfferOpenedUnlinked() {
        String candidate = insertCandidate();
        String application = insertApplication(candidate, practicePositionUuid, "INTERVIEW_2", null);

        changeStage(application, RecruitmentStage.OFFER, false);

        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.OFFER_OPENED, event.getEventType());
        assertEquals(candidate, event.getCandidateUuid());
        assertEquals(application, event.getApplicationUuid());
        assertEquals(practicePositionUuid, event.getPositionUuid());
        assertEquals(RecruitmentActorType.USER, event.getActorType());
        assertEquals(actor.toString(), event.getActorUuid());
        assertEquals(RecruitmentEventVisibility.NORMAL, event.getVisibility());
        assertTrue(event.getPayload().contains("\"dossier_linked\":false"));
        assertFalse(event.getPayload().contains("dossier_uuid"),
                "no dossier → no dossier_uuid key in the payload");
        assertTrue(event.getPayload().contains("\"from_stage\":\"INTERVIEW_2\""));
        assertTrue(event.getPayload().contains("\"position_title\":\"Bridge Consultant\""));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void offerEntry_withDossier_linksIt() {
        String candidate = insertCandidate();
        String dossier = insertDossier(candidate);
        String application = insertApplication(candidate, practicePositionUuid, "INTERVIEW_2", null);

        changeStage(application, RecruitmentStage.OFFER, false);

        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.OFFER_OPENED, event.getEventType());
        assertTrue(event.getPayload().contains("\"dossier_linked\":true"));
        assertTrue(event.getPayload().contains("\"dossier_uuid\":\"" + dossier + "\""));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void offerReEntry_emitsAgain_stillLinkedToTheSameDossier() {
        String candidate = insertCandidate();
        String dossier = insertDossier(candidate);
        String application = insertApplication(candidate, practicePositionUuid, "INTERVIEW_2", null);

        changeStage(application, RecruitmentStage.OFFER, false);
        changeStage(application, RecruitmentStage.INTERVIEW_2, false); // back-move out
        changeStage(application, RecruitmentStage.OFFER, false);       // re-entry

        List<RecruitmentEvent> offerOpened = eventsFor(candidate).stream()
                .filter(e -> e.getEventType() == RecruitmentEventType.OFFER_OPENED)
                .toList();
        assertEquals(2, offerOpened.size(),
                "the timeline is a truthful log — every entry into OFFER is recorded");
        for (RecruitmentEvent event : offerOpened) {
            assertTrue(event.getPayload().contains("\"dossier_uuid\":\"" + dossier + "\""),
                    "the link is a lookup — both entries reference the same dossier");
            RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
        }
    }

    @Test
    void partnerTrackOfferEntry_carriesCircleVisibility() {
        String candidate = insertCandidate();
        String application = insertApplication(candidate, partnerPositionUuid, "INTERVIEW_3", null);

        changeStage(application, RecruitmentStage.OFFER, false);

        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.OFFER_OPENED, event.getEventType());
        assertEquals(RecruitmentEventVisibility.CIRCLE, event.getVisibility(),
                "partner-track bridge events are circle-scoped");
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void fastTrackSkipIntoOffer_alsoFiresTheBridge() {
        String candidate = insertCandidate();
        String application = insertApplication(candidate, practicePositionUuid, "SCREENING", null);

        changeStage(application, RecruitmentStage.OFFER, true); // recruiter fast-track

        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.OFFER_OPENED, event.getEventType());
        assertTrue(event.getPayload().contains("\"from_stage\":\"SCREENING\""));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    // ---- B1b: signature-send gate ----------------------------------------------------

    @Test
    void gate_practiceTrackOfferWithoutTeam_blocksWith409TeamNotAssigned() {
        String candidate = insertCandidate();
        insertApplication(candidate, practicePositionUuid, "OFFER", null);

        WebApplicationException blocked = assertThrows(WebApplicationException.class,
                () -> assertSendAllowed(candidate));
        assertEquals(409, blocked.getResponse().getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) blocked.getResponse().getEntity();
        assertEquals("TEAM_NOT_ASSIGNED", body.get("error"));
        assertTrue(body.get("message").contains("no team assigned"),
                "the message explains the consequence in plain language");
        assertTrue(body.get("guidance").contains("Assign a team"),
                "the guidance tells the user what to do next");
    }

    @Test
    void gate_passes_whenTeamIsAssigned() {
        String candidate = insertCandidate();
        insertApplication(candidate, practicePositionUuid, "OFFER", UUID.randomUUID().toString());
        assertSendAllowed(candidate); // no throw
    }

    @Test
    void gate_passes_beforeOffer() {
        String candidate = insertCandidate();
        insertApplication(candidate, practicePositionUuid, "INTERVIEW_1", null);
        assertSendAllowed(candidate); // legacy allows sending at any time pre-OFFER
    }

    @Test
    void gate_passes_onPartnerTrackOfferWithoutTeam() {
        String candidate = insertCandidate();
        insertApplication(candidate, partnerPositionUuid, "OFFER", null);
        assertSendAllowed(candidate); // team decisions are a practice-track concept
    }

    @Test
    void gate_passes_forCandidateWithoutApplications() {
        String candidate = insertCandidate();
        assertSendAllowed(candidate); // pre-ATS dossier-only flow stays untouched
    }

    @Test
    void gate_passes_whenTheApplicationIsAlreadyHired() {
        String candidate = insertCandidate();
        insertApplication(candidate, practicePositionUuid, "HIRED", null);
        assertSendAllowed(candidate); // stage HIRED never matches stage OFFER
    }

    // ---- B1c: SIGNING_COMPLETED durable idempotency ----------------------------------

    @Test
    void signingCompleted_appendsOnce_durablyIdempotentOnCaseKey() {
        String candidate = insertCandidate();
        String dossier = insertDossier(candidate);
        String caseKey = "bridge-test-case-" + UUID.randomUUID();

        boolean first = inTx(() -> bridge.recordSigningCompletedIfNew(caseKey, dossier));
        boolean second = inTx(() -> bridge.recordSigningCompletedIfNew(caseKey, dossier));

        assertTrue(first, "first call appends");
        assertFalse(second, "second call is a durable no-op — the DB claim is the dedup");
        List<RecruitmentEvent> events = eventsFor(candidate).stream()
                .filter(e -> e.getEventType() == RecruitmentEventType.SIGNING_COMPLETED)
                .toList();
        assertEquals(1, events.size());
        assertEquals(1L, claimCountFor(caseKey),
                "the appending transaction also committed its atomic case claim");
        RecruitmentEvent event = events.get(0);
        assertEquals(RecruitmentActorType.SYSTEM, event.getActorType());
        assertNull(event.getActorUuid());
        assertNull(event.getApplicationUuid(), "no open OFFER application → candidate subject only");
        assertEquals(RecruitmentEventVisibility.NORMAL, event.getVisibility());
        assertTrue(event.getPayload().contains("\"case_key\":\"" + caseKey + "\""));
        assertTrue(event.getPayload().contains("\"dossier_uuid\":\"" + dossier + "\""));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void signingCompleted_withOpenOfferApplication_carriesApplicationSubjects() {
        String candidate = insertCandidate();
        String dossier = insertDossier(candidate);
        String application = insertApplication(candidate, partnerPositionUuid, "OFFER", null);

        inTx(() -> bridge.recordSigningCompletedIfNew("case-" + UUID.randomUUID(), dossier));

        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.SIGNING_COMPLETED, event.getEventType());
        assertEquals(application, event.getApplicationUuid());
        assertEquals(partnerPositionUuid, event.getPositionUuid());
        assertEquals(RecruitmentEventVisibility.CIRCLE, event.getVisibility(),
                "partner-track OFFER application → circle-scoped completion event");
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void signingCompleted_caseAlreadyClaimed_isNoOp_evenWhenNoEventExists() {
        // MEDIUM-1: another instance's transaction committed the atomic
        // claim (e.g. the ECS Express cutover overlap) but its event is not
        // visible to our COUNT check — the claim alone must block the
        // append. Seed ONLY the claim row, no event.
        String candidate = insertCandidate();
        String dossier = insertDossier(candidate);
        String caseKey = "bridge-claimed-case-" + UUID.randomUUID();
        insertClaim(caseKey, candidate);

        boolean appended = inTx(() -> bridge.recordSigningCompletedIfNew(caseKey, dossier));

        assertFalse(appended,
                "a pre-existing claim wins even when the event-store fast path finds nothing");
        assertTrue(eventsFor(candidate).isEmpty(),
                "no event may be appended for an already-claimed case");
        assertEquals(1L, claimCountFor(caseKey), "the seeded claim row is untouched");
    }

    @Test
    void signingCompleted_terminalPartnerApplication_failsClosedToCircle() {
        // MEDIUM-2: the candidate's partner-track application is already
        // terminal (rejected) and an unrelated practice-track application
        // sits at OFFER when the batchlet runs. Subjects follow the open
        // OFFER application, but visibility must fail closed to CIRCLE —
        // the signed contract may belong to the confidential partner hire.
        String candidate = insertCandidate();
        String dossier = insertDossier(candidate);
        insertRejectedApplication(candidate, partnerPositionUuid, "OFFER");
        String practiceApp = insertApplication(candidate, practicePositionUuid, "OFFER", null);

        inTx(() -> bridge.recordSigningCompletedIfNew("case-" + UUID.randomUUID(), dossier));

        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.SIGNING_COMPLETED, event.getEventType());
        assertEquals(practiceApp, event.getApplicationUuid(),
                "subjects still follow the open OFFER application");
        assertEquals(practicePositionUuid, event.getPositionUuid());
        assertEquals(RecruitmentEventVisibility.CIRCLE, event.getVisibility(),
                "ANY partner-track application — open or terminal — fails closed to CIRCLE");
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    // ---- B1d: conversion bridge ------------------------------------------------------

    @Test
    void conversion_withOfferApplication_hiresItAndAppendsBothEvents() {
        String candidate = insertCandidate();
        String team = UUID.randomUUID().toString();
        String application = insertApplication(candidate, practicePositionUuid, "OFFER", team);
        String newUser = UUID.randomUUID().toString();
        LocalDate start = LocalDate.now().plusMonths(2).withDayOfMonth(1);

        inTx(() -> {
            bridge.onCandidateConverted(RecruitmentCandidate.findById(candidate),
                    newUser, team, start, actor);
            return null;
        });

        RecruitmentApplication hired = reloadApplication(application);
        assertEquals(RecruitmentStage.HIRED, hired.getStage());
        assertNull(hired.getTerminal(), "HIRED is a stage — terminal stays NULL");

        List<RecruitmentEvent> events = eventsFor(candidate);
        assertEquals(List.of(RecruitmentEventType.APPLICATION_STAGE_CHANGED,
                        RecruitmentEventType.CANDIDATE_HIRED),
                events.stream().map(RecruitmentEvent::getEventType).toList());

        RecruitmentEvent stageChanged = events.get(0);
        assertEquals(application, stageChanged.getApplicationUuid());
        assertTrue(stageChanged.getPayload().contains("\"from\":\"OFFER\""));
        assertTrue(stageChanged.getPayload().contains("\"to\":\"HIRED\""));
        assertTrue(stageChanged.getPayload().contains("\"direction\":\"FORWARD\""));
        assertTrue(stageChanged.getPayload().contains("\"skipped_stages\":false"));

        RecruitmentEvent candidateHired = events.get(1);
        assertEquals(application, candidateHired.getApplicationUuid());
        assertEquals(practicePositionUuid, candidateHired.getPositionUuid());
        assertEquals(actor.toString(), candidateHired.getActorUuid());
        assertTrue(candidateHired.getPayload().contains("\"user_uuid\":\"" + newUser + "\""));
        assertTrue(candidateHired.getPayload().contains("\"team_uuid\":\"" + team + "\""));
        assertTrue(candidateHired.getPayload().contains(
                "\"planned_start_date\":\"" + start + "\""));
        assertFalse(candidateHired.getPayload().contains("salary"),
                "salary must NEVER reach the event payload");
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    @Test
    void conversion_leavesOtherOpenApplicationsUntouched() {
        String candidate = insertCandidate();
        String offerApp = insertApplication(candidate, practicePositionUuid, "OFFER", null);
        String interviewApp = insertApplication(candidate, partnerPositionUuid, "INTERVIEW_2", null);

        inTx(() -> {
            bridge.onCandidateConverted(RecruitmentCandidate.findById(candidate),
                    UUID.randomUUID().toString(), null, LocalDate.now().plusMonths(1), actor);
            return null;
        });

        assertEquals(RecruitmentStage.HIRED, reloadApplication(offerApp).getStage());
        RecruitmentApplication untouched = reloadApplication(interviewApp);
        assertEquals(RecruitmentStage.INTERVIEW_2, untouched.getStage(),
                "spec is silent on other open applications — nothing auto-closes them");
        assertNull(untouched.getTerminal());

        long stageChangedCount = eventsFor(candidate).stream()
                .filter(e -> e.getEventType() == RecruitmentEventType.APPLICATION_STAGE_CHANGED)
                .count();
        assertEquals(1, stageChangedCount, "only the OFFER application moved");
    }

    @Test
    void conversion_withoutApplications_emitsCandidateHiredOnly() {
        String candidate = insertCandidate();
        String newUser = UUID.randomUUID().toString();

        inTx(() -> {
            bridge.onCandidateConverted(RecruitmentCandidate.findById(candidate),
                    newUser, null, LocalDate.now().plusMonths(1), actor);
            return null;
        });

        List<RecruitmentEvent> events = eventsFor(candidate);
        assertEquals(1, events.size(), "legacy dossier-only conversion still gets its event");
        RecruitmentEvent event = events.get(0);
        assertEquals(RecruitmentEventType.CANDIDATE_HIRED, event.getEventType());
        assertNull(event.getApplicationUuid());
        assertNull(event.getPositionUuid());
        assertTrue(event.getPayload().contains("\"user_uuid\":\"" + newUser + "\""));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void conversion_onPartnerTrack_bothEventsCarryCircleVisibility() {
        String candidate = insertCandidate();
        insertApplication(candidate, partnerPositionUuid, "OFFER", null);

        inTx(() -> {
            bridge.onCandidateConverted(RecruitmentCandidate.findById(candidate),
                    UUID.randomUUID().toString(), null, LocalDate.now().plusMonths(1), actor);
            return null;
        });

        List<RecruitmentEvent> events = eventsFor(candidate);
        assertEquals(2, events.size());
        for (RecruitmentEvent event : events) {
            assertEquals(RecruitmentEventVisibility.CIRCLE, event.getVisibility(),
                    event.getEventType() + " must be circle-scoped on partner track");
        }
    }

    @Test
    void conversion_rollback_leavesNoEventsAndNoStageChange() {
        String candidate = insertCandidate();
        String application = insertApplication(candidate, practicePositionUuid, "OFFER", null);

        // Simulate a later conversion step failing INSIDE the same
        // transaction — "no partial hires": the recorder persists in the
        // caller's tx, so the rollback removes both state and events.
        assertThrows(RuntimeException.class, () ->
                QuarkusTransaction.requiringNew().run(() -> {
                    em.clear();
                    bridge.onCandidateConverted(RecruitmentCandidate.findById(candidate),
                            UUID.randomUUID().toString(), null,
                            LocalDate.now().plusMonths(1), actor);
                    throw new RuntimeException("simulated conversion failure after the bridge");
                }));

        assertEquals(RecruitmentStage.OFFER, reloadApplication(application).getStage(),
                "the stage change rolled back with the transaction");
        assertTrue(eventsFor(candidate).isEmpty(), "no events survive the rollback");
    }

    // ---- Helpers ---------------------------------------------------------------------

    private void changeStage(String applicationUuid, RecruitmentStage target,
                             boolean mayFastTrack) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.clear();
            RecruitmentApplication managed = RecruitmentApplication.findById(applicationUuid);
            applicationService.changeStage(managed,
                    RecruitmentPosition.findById(managed.getPositionUuid()), target,
                    mayFastTrack, actor);
        });
    }

    private void assertSendAllowed(String candidateUuid) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.clear();
            bridge.assertSignatureSendAllowed(RecruitmentCandidate.findById(candidateUuid));
        });
    }

    private <T> T inTx(java.util.concurrent.Callable<T> action) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            return action.call();
        });
    }

    private RecruitmentApplication reloadApplication(String uuid) {
        em.clear();
        return RecruitmentApplication.findById(uuid);
    }

    private List<RecruitmentEvent> eventsFor(String candidateUuid) {
        em.clear();
        return RecruitmentEvent.list("candidateUuid = ?1 order by seq", candidateUuid);
    }

    private RecruitmentEvent lastEvent(String candidateUuid) {
        List<RecruitmentEvent> events = eventsFor(candidateUuid);
        assertFalse(events.isEmpty(), "expected at least one event for " + candidateUuid);
        return events.get(events.size() - 1);
    }

    private String insertCandidate() {
        String uuid = UUID.randomUUID().toString();
        candidateUuids.add(uuid);
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_candidates
                                    (uuid, first_name, last_name, status, source,
                                     created_by_useruuid, created_at, updated_at)
                                VALUES (:uuid, :first, 'Fixture', 'ACTIVE', 'OTHER',
                                        :actor, NOW(), NOW())
                                """)
                        .setParameter("uuid", uuid)
                        .setParameter("first", PII_SENTINEL + "-Bridge")
                        .setParameter("actor", actor.toString())
                        .executeUpdate());
        return uuid;
    }

    private String insertDossier(String candidateUuid) {
        String uuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO candidate_dossiers
                                    (uuid, candidate_uuid, template_uuid, status)
                                VALUES (:uuid, :candidate, :template, 'OPEN')
                                """)
                        .setParameter("uuid", uuid)
                        .setParameter("candidate", candidateUuid)
                        .setParameter("template", UUID.randomUUID().toString())
                        .executeUpdate());
        return uuid;
    }

    private String insertApplication(String candidateUuid, String positionUuid,
                                     String stage, String assignedTeamUuid) {
        String uuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_applications
                                    (uuid, candidate_uuid, position_uuid, stage, assigned_team_uuid,
                                     stage_entered_at, created_at, updated_at, created_by)
                                VALUES (:uuid, :candidate, :position, :stage, :team,
                                        NOW(3), NOW(), NOW(), 'test')
                                """)
                        .setParameter("uuid", uuid)
                        .setParameter("candidate", candidateUuid)
                        .setParameter("position", positionUuid)
                        .setParameter("stage", stage)
                        .setParameter("team", assignedTeamUuid)
                        .executeUpdate());
        return uuid;
    }

    /** A terminal (REJECTED) application — excluded from every open-application query. */
    private void insertRejectedApplication(String candidateUuid, String positionUuid,
                                           String stage) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_applications
                                    (uuid, candidate_uuid, position_uuid, stage, terminal,
                                     rejection_reason_code, stage_entered_at,
                                     created_at, updated_at, created_by)
                                VALUES (:uuid, :candidate, :position, :stage, 'REJECTED',
                                        'PROFILE_MISMATCH', NOW(3), NOW(), NOW(), 'test')
                                """)
                        .setParameter("uuid", UUID.randomUUID().toString())
                        .setParameter("candidate", candidateUuid)
                        .setParameter("position", positionUuid)
                        .setParameter("stage", stage)
                        .executeUpdate());
    }

    /** Simulate another instance's committed atomic case claim (no event). */
    private void insertClaim(String caseKey, String candidateUuid) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_signing_completed_cases
                                    (case_key, candidate_uuid)
                                VALUES (:caseKey, :candidate)
                                """)
                        .setParameter("caseKey", caseKey)
                        .setParameter("candidate", candidateUuid)
                        .executeUpdate());
    }

    private long claimCountFor(String caseKey) {
        return inTx(() -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM recruitment_signing_completed_cases "
                                + "WHERE case_key = :caseKey")
                .setParameter("caseKey", caseKey)
                .getSingleResult()).longValue());
    }

    private void insertPosition(String uuid, String title, String track, String stageSetJson) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, stage_set,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :title, :track, :stageSet,
                                'GREEN', 'OPEN', NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("title", title)
                .setParameter("track", track)
                .setParameter("stageSet", stageSetJson)
                .executeUpdate();
    }
}
