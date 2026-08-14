package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
import dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The kill-pod-mid-saga resume test (plan closing phases §1; "deploy
 * resilience is a feature, test it"). A deploy can kill the JVM at the
 * WORST possible moment of the finalization saga: AFTER
 * {@code RecruitmentInterviewService.create} committed the real
 * interview, BEFORE the request transitioned to SCHEDULED. This test
 * seeds exactly the state such a crash leaves behind — request
 * FINALIZING, slot SELECTED, a SCHEDULED interview already existing at
 * the slot's time — and drives the next advance sweep over it:
 * <ul>
 *   <li>the interview-existence guard resumes WITHOUT creating a second
 *       meeting (and without touching Graph at all — the guard
 *       short-circuits the recheck, so no calendar bridge is needed
 *       here);</li>
 *   <li>the request completes to SCHEDULED, the slot to FINALIZED;</li>
 *   <li>the completion notices are enqueued exactly once — a second
 *       sweep over the now-terminal request changes nothing (the outbox
 *       idempotency keys and the terminal-status guard both hold).</li>
 * </ul>
 * Runs in the {@code @QuarkusTest} tier against the local docker DB
 * (V495–V497 applied) — not in the CI gate; the validation phase runs
 * it explicitly.
 */
@QuarkusTest
class RecruitmentSchedulingSagaResumeTest {

    @Inject
    EntityManager em;

    @Inject
    RecruitmentSchedulingOrchestrator orchestrator;

    private String practiceUuid;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private String recruiterUuid;
    private String interviewerUuid;
    private String requestUuid;
    private String slotUuid;
    private String interviewUuid;

    /** Tomorrow 10:00 — inside the window, after any not-before rule. */
    private LocalDateTime slotStart;

    @BeforeEach
    void seedCrashedSagaState() {
        practiceUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        recruiterUuid = UUID.randomUUID().toString();
        interviewerUuid = UUID.randomUUID().toString();
        slotStart = LocalDate.now().plusDays(1).atTime(10, 0);

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, recruiterUuid, "Saga", "Recruiter");
            P8ProfileFixtures.insertUser(em, interviewerUuid, "Saga", "Interviewer");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Saga Resume Position",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertCandidate(em, candidateUuid, "Saga", "Candidate",
                    "ACTIVE", null, null, "test");
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "INTERVIEW_1");

            RecruitmentSchedulingRequest request = new RecruitmentSchedulingRequest();
            request.setApplicationUuid(applicationUuid);
            request.setRecruiterUuid(recruiterUuid);
            request.setKind(RecruitmentInterviewKind.ROUND);
            request.setRound(1);
            request.setDurationMinutes(60);
            request.setInterviewerUuids(List.of(interviewerUuid));
            request.setOptionalInterviewerUuids(List.of());
            request.setRequestedOptions(1);
            request.setWindowStart(LocalDate.now());
            request.setWindowEnd(LocalDate.now().plusDays(14));
            request.setAutomationDeadline(LocalDateTime.now().plusDays(14));
            request.setStatus(SchedulingRequestStatus.FINALIZING);
            request.persist();
            requestUuid = request.getUuid();

            RecruitmentProposedSlot slot = new RecruitmentProposedSlot();
            slot.setRequestUuid(requestUuid);
            slot.setOptionNo(1);
            slot.setSlotStart(slotStart);
            slot.setSlotEnd(slotStart.plusHours(1));
            slot.setStatus(ProposedSlotStatus.SELECTED);
            slot.persist();
            slotUuid = slot.getUuid();

            // The crash artifact: the interview the previous attempt
            // ALREADY created (same application, same time, SCHEDULED) —
            // the guard's exact lookup key.
            RecruitmentInterview interview = new RecruitmentInterview();
            interview.setApplicationUuid(applicationUuid);
            interview.setKind(RecruitmentInterviewKind.ROUND);
            interview.setRound(1);
            interview.setScheduledAt(slotStart);
            interview.setDurationMinutes(60);
            interview.setInterviewerUuids(List.of(interviewerUuid));
            interview.setStatus(RecruitmentInterviewStatus.SCHEDULED);
            interview.persist();
            interviewUuid = interview.getUuid();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_scheduling_outbox WHERE request_uuid = :r")
                    .setParameter("r", requestUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_proposed_slot WHERE request_uuid = :r")
                    .setParameter("r", requestUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_scheduling_request WHERE uuid = :r")
                    .setParameter("r", requestUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_interviews WHERE application_uuid = :a")
                    .setParameter("a", applicationUuid).executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid),
                    List.of(recruiterUuid, interviewerUuid), practiceUuid);
        });
    }

    @Test
    void resumedSweep_completesTheSaga_withoutADuplicateMeeting() {
        // The "restart": the next advance sweep picks the request up.
        QuarkusTransaction.requiringNew().run(() -> orchestrator.advance(requestUuid));

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, RecruitmentInterview.count("applicationUuid = ?1", applicationUuid),
                    "the interview-existence guard must resume, never create a second meeting");
            RecruitmentSchedulingRequest request =
                    RecruitmentSchedulingRequest.findById(requestUuid);
            assertEquals(SchedulingRequestStatus.SCHEDULED, request.getStatus(),
                    "the resumed saga must complete the FINALIZING → SCHEDULED transition");
            RecruitmentProposedSlot slot = RecruitmentProposedSlot.findById(slotUuid);
            assertEquals(ProposedSlotStatus.FINALIZED, slot.getStatus());
            assertEquals(1, outboxCount(SchedulingOutboxAction.SEND_RECRUITER_DM),
                    "exactly one SCHEDULED recruiter notice");
            assertEquals(1, outboxCount(SchedulingOutboxAction.NOTIFY_FINALIZED),
                    "exactly one interviewer finalize notice (spec §16.3)");
        });

        // A second sweep over the now-terminal request is a no-op: the
        // terminal guard skips it and the idempotency keys hold.
        QuarkusTransaction.requiringNew().run(() -> orchestrator.advance(requestUuid));
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, RecruitmentInterview.count("applicationUuid = ?1", applicationUuid));
            assertEquals(1, outboxCount(SchedulingOutboxAction.SEND_RECRUITER_DM));
            assertEquals(1, outboxCount(SchedulingOutboxAction.NOTIFY_FINALIZED));
        });
    }

    private long outboxCount(SchedulingOutboxAction action) {
        return RecruitmentSchedulingOutbox.count(
                "requestUuid = ?1 and action = ?2", requestUuid, action);
    }
}
