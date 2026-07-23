package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.ApplicationRejectRequest;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 DoD at the service layer (spec §4.2 invariants):
 * <ul>
 *   <li>the transition matrix — illegal moves conflict (409), legal ones
 *       append {@code APPLICATION_STAGE_CHANGED} with from/to/direction;</li>
 *   <li>the partner-referral reject guard (403 + guidance payload);</li>
 *   <li>retention bookkeeping on the LAST open application only;</li>
 *   <li>assign-team accepts cross-practice and practice-less teams;</li>
 *   <li>return-to-pool → silver medalist + consent {@code REQUESTED};</li>
 *   <li>{@code assertNoPiiInPayload} green on every appended event
 *       ({@code PII_SENTINEL} in the free-text fixtures).</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentApplicationServiceIntegrationTest {

    @Inject
    RecruitmentApplicationService applicationService;

    @Inject
    EntityManager em;

    private final UUID actor = UUID.randomUUID();

    private String practiceA;
    private String practiceB;
    private String teamOnPracticeB;
    private String practiceLessTeam;
    private String positionUuid;        // PRACTICE_TEAM on practiceA, full stage set
    private String partnerPositionUuid; // PARTNER, full set + INTERVIEW_3

    private final List<String> candidateUuids = new ArrayList<>();
    private final List<String> positionUuids = new ArrayList<>();

    @BeforeEach
    void seedFixtures() {
        practiceA = UUID.randomUUID().toString();
        practiceB = UUID.randomUUID().toString();
        teamOnPracticeB = UUID.randomUUID().toString();
        practiceLessTeam = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        positionUuids.add(positionUuid);
        positionUuids.add(partnerPositionUuid);

        QuarkusTransaction.requiringNew().run(() -> {
            insertPractice(practiceA);
            insertPractice(practiceB);
            insertTeam(teamOnPracticeB, practiceB);
            insertTeam(practiceLessTeam, null);
            insertPosition(positionUuid, "Consultant", "PRACTICE_TEAM", practiceA,
                    "[\"SCREENING\",\"INTERVIEW_1\",\"INTERVIEW_2\",\"OFFER\",\"HIRED\"]");
            insertPosition(partnerPositionUuid, "Partner hire", "PARTNER", null,
                    "[\"SCREENING\",\"INTERVIEW_1\",\"INTERVIEW_2\",\"INTERVIEW_3\",\"OFFER\",\"HIRED\"]");
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
            em.createNativeQuery("DELETE FROM team WHERE uuid IN :t")
                    .setParameter("t", List.of(teamOnPracticeB, practiceLessTeam)).executeUpdate();
            em.createNativeQuery("DELETE FROM practice WHERE uuid IN :pr")
                    .setParameter("pr", List.of(practiceA, practiceB)).executeUpdate();
        });
        candidateUuids.clear();
        positionUuids.clear();
    }

    // ---- Create ------------------------------------------------------------------

    @Test
    void create_startsInFirstStage_emitsCreatedWithAllSubjects() {
        String candidate = insertCandidate(null);
        RecruitmentApplication application = create(candidate, positionUuid);

        assertEquals(RecruitmentStage.SCREENING, application.getStage());
        assertNull(application.getTerminal());
        assertNotNull(application.getStageEnteredAt());

        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.APPLICATION_CREATED, event.getEventType());
        assertEquals(candidate, event.getCandidateUuid());
        assertEquals(application.getUuid(), event.getApplicationUuid());
        assertEquals(positionUuid, event.getPositionUuid());
        assertTrue(event.getPayload().contains("\"initial_stage\":\"SCREENING\""));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void create_duplicateOpenApplication_conflicts() {
        String candidate = insertCandidate(null);
        create(candidate, positionUuid);
        assertThrows(BusinessRuleViolation.class, () -> create(candidate, positionUuid),
                "one open application per candidate+position pair");
    }

    @Test
    void create_onNonOpenPosition_conflicts() {
        String candidate = insertCandidate(null);
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_positions SET status = 'ON_HOLD' WHERE uuid = :p")
                        .setParameter("p", positionUuid).executeUpdate());
        assertThrows(BusinessRuleViolation.class, () -> create(candidate, positionUuid));
    }

    @Test
    void create_terminalCandidate_conflicts() {
        String candidate = insertCandidate(null);
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates SET status = 'DECLINED' WHERE uuid = :c")
                        .setParameter("c", candidate).executeUpdate());
        assertThrows(BusinessRuleViolation.class, () -> create(candidate, positionUuid));
    }

    @Test
    void create_pooledCandidate_unpoolsAndResumesTheProcess() {
        String candidate = insertCandidate(null);
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                UPDATE recruitment_candidates
                                SET status = 'POOLED', pool_status = 'PROSPECT',
                                    process_ended_at = NOW(3), retention_deadline = NOW(3)
                                WHERE uuid = :c
                                """)
                        .setParameter("c", candidate).executeUpdate());

        create(candidate, positionUuid);

        RecruitmentCandidate reloaded = reloadCandidate(candidate);
        assertEquals(CandidateStatus.ACTIVE, reloaded.getStatus(), "pool → pipeline reactivates");
        assertNull(reloaded.getPoolStatus());
        assertNull(reloaded.getProcessEndedAt(), "a resumed process stops the retention clock");
        assertNull(reloaded.getRetentionDeadline());

        List<RecruitmentEventType> types = eventTypes(candidate);
        assertEquals(List.of(RecruitmentEventType.CANDIDATE_UNPOOLED,
                RecruitmentEventType.APPLICATION_CREATED), types);
    }

    // ---- Stage moves ---------------------------------------------------------------

    @Test
    void legalForwardMove_appendsStageChangedWithDirectionForward() {
        String candidate = insertCandidate(null);
        RecruitmentApplication application = create(candidate, positionUuid);

        changeStage(application, RecruitmentStage.INTERVIEW_1, false);

        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.APPLICATION_STAGE_CHANGED, event.getEventType());
        assertTrue(event.getPayload().contains("\"from\":\"SCREENING\""));
        assertTrue(event.getPayload().contains("\"to\":\"INTERVIEW_1\""));
        assertTrue(event.getPayload().contains("\"direction\":\"FORWARD\""));
        assertTrue(event.getPayload().contains("\"skipped_stages\":false"));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void backMove_isLegal_andFlaggedOnTheEvent() {
        String candidate = insertCandidate(null);
        RecruitmentApplication application = create(candidate, positionUuid);
        changeStage(application, RecruitmentStage.INTERVIEW_1, false);

        changeStage(application, RecruitmentStage.SCREENING, false);

        RecruitmentEvent event = lastEvent(candidate);
        assertTrue(event.getPayload().contains("\"direction\":\"BACK\""),
                "no silent rewinds — the timeline shows the back move");
    }

    @Test
    void forwardSkip_requiresRecruiterOrOwnerTier() {
        String candidate = insertCandidate(null);
        RecruitmentApplication application = create(candidate, positionUuid);

        WebApplicationException denied = assertThrows(WebApplicationException.class,
                () -> changeStage(application, RecruitmentStage.OFFER, false));
        assertEquals(403, denied.getResponse().getStatus());
        assertTrue(String.valueOf(denied.getResponse().getEntity()).contains("guidance"),
                "the 403 carries a guidance payload");

        // Recruiter/owner tier may fast-track. Since P10 the skip INTO
        // OFFER also fires the offer bridge, so the stage-change event is
        // followed by OFFER_OPENED — the timeline shows both.
        changeStage(application, RecruitmentStage.OFFER, true);
        List<RecruitmentEvent> events = eventsFor(candidate);
        RecruitmentEvent stageChanged = events.get(events.size() - 2);
        assertEquals(RecruitmentEventType.APPLICATION_STAGE_CHANGED, stageChanged.getEventType());
        assertTrue(stageChanged.getPayload().contains("\"skipped_stages\":true"));
        assertEquals(RecruitmentEventType.OFFER_OPENED, lastEvent(candidate).getEventType(),
                "a fast-track entry into OFFER fires the P10 offer bridge");
    }

    @Test
    void illegalTransitions_conflict() {
        String candidate = insertCandidate(null);
        RecruitmentApplication application = create(candidate, positionUuid);

        // Not part of this position's stage set (INTERVIEW_3 is partner-only).
        assertThrows(BusinessRuleViolation.class,
                () -> changeStage(application, RecruitmentStage.INTERVIEW_3, true));
        // HIRED never via the stage API.
        assertThrows(BusinessRuleViolation.class,
                () -> changeStage(application, RecruitmentStage.HIRED, true));
        // No-op move.
        assertThrows(BusinessRuleViolation.class,
                () -> changeStage(application, RecruitmentStage.SCREENING, true));
        // Terminal application refuses further moves.
        reject(application, candidate, RecruitmentRejectionReason.OTHER, null, true);
        assertThrows(BusinessRuleViolation.class,
                () -> changeStage(application, RecruitmentStage.INTERVIEW_1, true));
    }

    // ---- Reject --------------------------------------------------------------------

    @Test
    void reject_recordsReasonCode_andPutsTheNoteInPii() {
        String candidate = insertCandidate(null);
        RecruitmentApplication application = create(candidate, positionUuid);
        changeStage(application, RecruitmentStage.INTERVIEW_1, false);

        reject(application, candidate, RecruitmentRejectionReason.EXPERIENCE_LEVEL,
                PII_SENTINEL + " came across too junior for the case team", true);

        assertEquals(RecruitmentApplicationTerminal.REJECTED, reload(application).getTerminal());
        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.APPLICATION_REJECTED, event.getEventType());
        assertTrue(event.getPayload().contains("\"reason_code\":\"EXPERIENCE_LEVEL\""));
        assertTrue(event.getPayload().contains("\"from_stage\":\"INTERVIEW_1\""));
        assertTrue(event.getPii().contains(PII_SENTINEL), "the free-text note lives in pii");
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void partnerReferralReject_blockedForNonRecruiter_allowedForRecruiter() {
        String sponsor = UUID.randomUUID().toString();
        String candidate = insertCandidate(sponsor);
        RecruitmentApplication application = create(candidate, positionUuid);

        WebApplicationException denied = assertThrows(WebApplicationException.class,
                () -> reject(application, candidate, RecruitmentRejectionReason.CULTURE_FIT,
                        null, false));
        assertEquals(403, denied.getResponse().getStatus());
        String entity = String.valueOf(denied.getResponse().getEntity());
        assertTrue(entity.contains("guidance"), "the 403 carries a guidance payload");
        assertTrue(entity.contains("Escalate to the recruiter"),
                "the guidance routes the teamlead to escalation");
        assertNull(reload(application).getTerminal(), "the application stays open");

        // The recruiter (or hiring owner) CAN reject.
        reject(application, candidate, RecruitmentRejectionReason.CULTURE_FIT, null, true);
        assertEquals(RecruitmentApplicationTerminal.REJECTED, reload(application).getTerminal());
    }

    // ---- Withdraw -------------------------------------------------------------------

    @Test
    void withdraw_setsTerminal_andPutsTheNoteInPii() {
        String candidate = insertCandidate(null);
        RecruitmentApplication application = create(candidate, positionUuid);

        mutate(() -> applicationService.withdraw(
                RecruitmentApplication.findById(application.getUuid()),
                RecruitmentPosition.findById(positionUuid),
                RecruitmentCandidate.findById(candidate),
                PII_SENTINEL + " accepted an offer elsewhere", actor));

        assertEquals(RecruitmentApplicationTerminal.WITHDRAWN, reload(application).getTerminal());
        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.APPLICATION_WITHDRAWN, event.getEventType());
        assertTrue(event.getPii().contains(PII_SENTINEL));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    // ---- Return to pool ---------------------------------------------------------------

    @Test
    void returnToPool_poolsSilverMedalist_andRequestsConsent() {
        String candidate = insertCandidate(null);
        RecruitmentApplication application = create(candidate, positionUuid);

        mutate(() -> applicationService.returnToPool(
                RecruitmentApplication.findById(application.getUuid()),
                RecruitmentPosition.findById(positionUuid),
                RecruitmentCandidate.findById(candidate), actor));

        assertEquals(RecruitmentApplicationTerminal.RETURNED_TO_POOL,
                reload(application).getTerminal());
        RecruitmentCandidate pooled = reloadCandidate(candidate);
        assertEquals(CandidateStatus.POOLED, pooled.getStatus());
        assertEquals(CandidatePoolStatus.SILVER_MEDALIST, pooled.getPoolStatus());

        em.clear();
        List<RecruitmentConsent> consents = RecruitmentConsent.list("candidateUuid", candidate);
        assertEquals(1, consents.size());
        assertEquals(RecruitmentConsentKind.TALENT_POOL_RETENTION, consents.get(0).getKind());
        assertEquals(RecruitmentConsentStatus.REQUESTED, consents.get(0).getStatus());
        assertNull(consents.get(0).getTokenHash(), "tokens are minted by P19, not P4");

        List<RecruitmentEventType> types = eventTypes(candidate);
        assertEquals(List.of(RecruitmentEventType.APPLICATION_CREATED,
                        RecruitmentEventType.CANDIDATE_POOLED,
                        RecruitmentEventType.CONSENT_REQUESTED), types);
        RecruitmentEvent pooledEvent = eventsFor(candidate).get(1);
        assertTrue(pooledEvent.getPayload().contains("\"pool_status\":\"SILVER_MEDALIST\""));
        assertTrue(pooledEvent.getPayload().contains("\"terminal\":\"RETURNED_TO_POOL\""));
        assertEquals(application.getUuid(), pooledEvent.getApplicationUuid(),
                "the pool event references the application that ended");
        eventsFor(candidate).forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    // ---- Retention bookkeeping ---------------------------------------------------------

    @Test
    void terminalOnLastOpenApplication_startsTheRetentionClock_terminalOnOneOfTwoDoesNot() {
        String candidate = insertCandidate(null);
        RecruitmentApplication first = create(candidate, positionUuid);
        RecruitmentApplication second = create(candidate, partnerPositionUuid);

        reject(first, candidate, RecruitmentRejectionReason.POSITION_FILLED, null, true);
        RecruitmentCandidate afterFirst = reloadCandidate(candidate);
        assertNull(afterFirst.getProcessEndedAt(),
                "one of two applications closed → the process is still running");
        assertNull(afterFirst.getRetentionDeadline());

        reject(second, candidate, RecruitmentRejectionReason.POSITION_FILLED, null, true);
        RecruitmentCandidate afterLast = reloadCandidate(candidate);
        assertNotNull(afterLast.getProcessEndedAt(), "last application closed → clock starts");
        assertNotNull(afterLast.getRetentionDeadline());
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC)
                .plusMonths(RecruitmentApplicationService.RETENTION_MONTHS);
        assertTrue(Math.abs(java.time.Duration.between(
                        expected, afterLast.getRetentionDeadline()).toMinutes()) < 5,
                "retention_deadline = process end + 6 months");
    }

    // ---- Assign team -------------------------------------------------------------------

    @Test
    void assignTeam_acceptsCrossPracticeAndPracticeLessTeams() {
        String candidate = insertCandidate(null);
        RecruitmentApplication application = create(candidate, positionUuid);

        // The position sits on practice A; the team belongs to practice B.
        assignTeam(application, teamOnPracticeB);
        assertEquals(teamOnPracticeB, reload(application).getAssignedTeamUuid());
        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.TEAM_ASSIGNED, event.getEventType());
        assertTrue(event.getPayload().contains(teamOnPracticeB),
                "TEAM_ASSIGNED payload carries the team uuid");

        // A practice-less team is just as valid.
        assignTeam(application, practiceLessTeam);
        assertEquals(practiceLessTeam, reload(application).getAssignedTeamUuid());

        // A nonexistent team is invalid input, not a state conflict.
        WebApplicationException unknown = assertThrows(WebApplicationException.class,
                () -> assignTeam(application, UUID.randomUUID().toString()));
        assertEquals(400, unknown.getResponse().getStatus());
    }

    // ---- Expected start date ------------------------------------------------------------

    @Test
    void expectedStartDate_setsAndEmits_noOpAppendsNothing_terminalConflicts() {
        String candidate = insertCandidate(null);
        RecruitmentApplication application = create(candidate, positionUuid);
        LocalDate startDate = LocalDate.now().plusMonths(2).withDayOfMonth(1);

        setExpectedStartDate(application, startDate);
        assertEquals(startDate, reload(application).getExpectedStartDate());
        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.APPLICATION_UPDATED, event.getEventType());
        assertTrue(event.getPayload().contains("expected_start_date"));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);

        int events = eventsFor(candidate).size();
        setExpectedStartDate(application, startDate);
        assertEquals(events, eventsFor(candidate).size(), "no-op set → no event");

        reject(application, candidate, RecruitmentRejectionReason.OTHER, null, true);
        assertThrows(BusinessRuleViolation.class,
                () -> setExpectedStartDate(application, startDate.plusMonths(1)));
    }

    // ---- Partner-track event visibility ---------------------------------------------------

    @Test
    void partnerTrackApplicationEvents_carryCircleVisibility() {
        String candidate = insertCandidate(null);
        create(candidate, partnerPositionUuid);

        RecruitmentEvent event = lastEvent(candidate);
        assertEquals(RecruitmentEventType.APPLICATION_CREATED, event.getEventType());
        assertEquals(RecruitmentEventVisibility.CIRCLE, event.getVisibility(),
                "partner-track application events are circle-scoped (P2 carry-over)");

        // Non-partner events stay NORMAL.
        String other = insertCandidate(null);
        create(other, positionUuid);
        assertEquals(RecruitmentEventVisibility.NORMAL, lastEvent(other).getVisibility());
    }

    // ---- Helpers ---------------------------------------------------------------------------

    /**
     * Run a mutation in a fresh transaction against MANAGED entities: one
     * {@code em.clear()} up front, then every load happens inside the same
     * persistence context — a clear between loads would detach the earlier
     * ones and silently drop their flushes.
     */
    private void mutate(Runnable mutation) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.clear();
            mutation.run();
        });
    }

    private RecruitmentApplication create(String candidateUuid, String positionUuid) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            return applicationService.create(
                    RecruitmentCandidate.findById(candidateUuid),
                    RecruitmentPosition.findById(positionUuid), actor);
        });
    }

    private void changeStage(RecruitmentApplication application, RecruitmentStage target,
                             boolean mayFastTrack) {
        mutate(() -> {
            RecruitmentApplication managed = RecruitmentApplication.findById(application.getUuid());
            applicationService.changeStage(managed,
                    RecruitmentPosition.findById(managed.getPositionUuid()), target,
                    mayFastTrack, actor);
        });
    }

    private void reject(RecruitmentApplication application, String candidateUuid,
                        RecruitmentRejectionReason reason, String note, boolean isRecruiterOrOwner) {
        mutate(() -> {
            RecruitmentApplication managed = RecruitmentApplication.findById(application.getUuid());
            applicationService.reject(managed,
                    RecruitmentPosition.findById(managed.getPositionUuid()),
                    RecruitmentCandidate.findById(candidateUuid),
                    new ApplicationRejectRequest(reason, note), isRecruiterOrOwner, actor);
        });
    }

    private void assignTeam(RecruitmentApplication application, String teamUuid) {
        mutate(() -> {
            RecruitmentApplication managed = RecruitmentApplication.findById(application.getUuid());
            applicationService.assignTeam(managed,
                    RecruitmentPosition.findById(managed.getPositionUuid()), teamUuid, actor);
        });
    }

    private void setExpectedStartDate(RecruitmentApplication application, LocalDate date) {
        mutate(() -> {
            RecruitmentApplication managed = RecruitmentApplication.findById(application.getUuid());
            applicationService.setExpectedStartDate(managed,
                    RecruitmentPosition.findById(managed.getPositionUuid()), date, actor);
        });
    }

    private RecruitmentApplication reload(RecruitmentApplication application) {
        em.clear();
        return RecruitmentApplication.findById(application.getUuid());
    }

    private RecruitmentCandidate reloadCandidate(String uuid) {
        em.clear();
        return RecruitmentCandidate.findById(uuid);
    }

    private List<RecruitmentEvent> eventsFor(String candidateUuid) {
        em.clear();
        return RecruitmentEvent.list("candidateUuid = ?1 order by seq", candidateUuid);
    }

    private List<RecruitmentEventType> eventTypes(String candidateUuid) {
        return eventsFor(candidateUuid).stream().map(RecruitmentEvent::getEventType).toList();
    }

    private RecruitmentEvent lastEvent(String candidateUuid) {
        List<RecruitmentEvent> events = eventsFor(candidateUuid);
        assertFalse(events.isEmpty(), "expected at least one event for " + candidateUuid);
        return events.get(events.size() - 1);
    }

    private String insertCandidate(String sponsoringPartnerUuid) {
        String uuid = UUID.randomUUID().toString();
        candidateUuids.add(uuid);
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_candidates
                                    (uuid, first_name, last_name, status, source,
                                     sponsoring_partner_uuid, created_by_useruuid,
                                     created_at, updated_at)
                                VALUES (:uuid, :first, 'Fixture', 'ACTIVE', :source, :sponsor,
                                        :actor, NOW(), NOW())
                                """)
                        .setParameter("uuid", uuid)
                        .setParameter("first", PII_SENTINEL + "-App")
                        .setParameter("source",
                                sponsoringPartnerUuid != null ? "PARTNER_REFERRAL" : "OTHER")
                        .setParameter("sponsor", sponsoringPartnerUuid)
                        .setParameter("actor", actor.toString())
                        .executeUpdate());
        return uuid;
    }

    private void insertPractice(String uuid) {
        em.createNativeQuery("""
                        INSERT INTO practice (code, uuid, name, active, sort_order,
                                              created_at, updated_at, created_by)
                        VALUES (:code, :uuid, 'App Fixture', 1, 999, NOW(), NOW(), 'test')
                        """)
                .setParameter("code", "A" + uuid.substring(0, 7))
                .setParameter("uuid", uuid)
                .executeUpdate();
    }

    private void insertTeam(String uuid, String practiceUuid) {
        em.createNativeQuery("""
                        INSERT INTO team (uuid, name, shortname, teamleadbonus, teambonus, practice_uuid)
                        VALUES (:uuid, 'App Fixture Team', 'AFT', 0, 0, :practice)
                        """)
                .setParameter("uuid", uuid)
                .setParameter("practice", practiceUuid)
                .executeUpdate();
    }

    private void insertPosition(String uuid, String title, String track,
                                String practiceUuid, String stageSetJson) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, practice_uuid, stage_set,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :title, :track, :practice, :stageSet,
                                'GREEN', 'OPEN', NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("title", title)
                .setParameter("track", track)
                .setParameter("practice", practiceUuid)
                .setParameter("stageSet", stageSetJson)
                .executeUpdate();
    }
}
