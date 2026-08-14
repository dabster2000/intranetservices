package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomsResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Method B advance sweep (plan §8.3): every minute it picks up
 * requests in transitional states and performs the next step — search,
 * propose, recheck, hold, expire, remind, hand back. Progress comes from
 * exactly two sources, both idempotent: this sweep and the inbound
 * events (Slack answers, recruiter actions, candidate selection).
 * Every step re-derives its work from persisted state, so a deploy
 * mid-step costs nothing — the next sweep continues where the state
 * says.
 * <p>
 * Each request advances in its own {@code REQUIRES_NEW} transaction;
 * optimistic {@code @Version} locking makes concurrent instances safe
 * (the loser's commit fails and the next sweep re-derives). Graph reads
 * happen inside the step transaction but are read-only probes; every
 * external WRITE goes through the outbox.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentSchedulingOrchestrator {

    /** Search again this much later when nothing (new) was plannable. */
    static final int SEARCH_RETRY_MINUTES = 15;

    /** Proposals need approval + candidate selection lead time — never
     * propose a slot starting sooner than this. */
    static final int MIN_LEAD_HOURS = 24;

    /** Requests advanced per sweep — bounds one sweep's worst case. */
    static final int MAX_REQUESTS_PER_SWEEP = 25;

    /** Structural handback reasons. */
    static final String REASON_AUTOMATION_DEADLINE = "AUTOMATION_DEADLINE";
    static final String REASON_WINDOW_EXHAUSTED = "WINDOW_EXHAUSTED";

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    RecruitmentCalendarService calendarService;

    @Inject
    RecruitmentSchedulingService schedulingService;

    @Inject
    SchedulingOutboxService outboxService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Scheduled(every = "1m", identity = "recruitment-scheduling-advance",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void advanceTimer() {
        if (!methodBFlag.isMethodBEnabled()) {
            return;
        }
        if (!calendarService.isEnabled()) {
            // Method B cannot search or hold without the Graph calendar
            // bridge; a mis-flagged environment must not spin.
            log.debug("Method B advance sweep skipped: graph.calendar.enabled=false");
            return;
        }
        advanceSweep();
    }

    /** One sweep over all due requests. Public for tests and ops. */
    public void advanceSweep() {
        LocalDateTime now = LocalDateTime.now();
        List<String> due = QuarkusTransaction.requiringNew().call(() ->
                RecruitmentSchedulingRequest
                        .<RecruitmentSchedulingRequest>find(
                                "status not in ?1 and (nextActionAt is null or nextActionAt <= ?2)",
                                RecruitmentSchedulingService.terminalStatuses(), now)
                        .page(0, MAX_REQUESTS_PER_SWEEP)
                        .list().stream()
                        .map(RecruitmentSchedulingRequest::getUuid)
                        .toList());
        for (String uuid : due) {
            try {
                QuarkusTransaction.requiringNew().run(() -> advance(uuid));
            } catch (Exception e) {
                // One stuck request must not stall the fleet; optimistic-
                // lock losers land here too and simply wait a sweep.
                log.warnf("Method B advance failed for request %s: %s", uuid, e.getMessage());
            }
        }
    }

    /** Advance one request one step. Runs inside REQUIRES_NEW. */
    void advance(String requestUuid) {
        RecruitmentSchedulingRequest request =
                RecruitmentSchedulingRequest.findById(requestUuid);
        if (request == null || request.getStatus().isTerminal()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();

        // The two-week anchor (spec §19.4). FINALIZING is exempt: a
        // running saga finishes; if it regresses, the next sweep lands
        // here again.
        if (now.isAfter(request.getAutomationDeadline())
                && request.getStatus() != SchedulingRequestStatus.FINALIZING) {
            schedulingService.handBack(request, null, REASON_AUTOMATION_DEADLINE);
            return;
        }

        if (request.getStatus() == SchedulingRequestStatus.DRAFT) {
            SchedulingStateMachine.require(SchedulingRequestStatus.DRAFT,
                    SchedulingRequestStatus.SEARCHING);
            request.setStatus(SchedulingRequestStatus.SEARCHING);
        }

        switch (request.getStatus()) {
            case SEARCHING, WAITING_FOR_INTERVIEWERS, HOLDING_OPTIONS, READY_FOR_CANDIDATE ->
                    pipelineStep(request, now);
            // WAITING_FOR_CANDIDATE / FINALIZING advance on candidate
            // events and the Phase 11 saga, not here.
            default -> {
            }
        }
    }

    /** The proposal-pipeline step: top up, then recompute the status. */
    private void pipelineStep(RecruitmentSchedulingRequest request, LocalDateTime now) {
        List<RecruitmentProposedSlot> slots = RecruitmentProposedSlot
                .list("requestUuid = ?1", request.getUuid());

        long live = slots.stream().filter(s -> s.getStatus().isLive()).count();
        boolean canTopUp = switch (request.getStatus()) {
            case SEARCHING, WAITING_FOR_INTERVIEWERS, HOLDING_OPTIONS -> true;
            default -> false;
        };
        if (canTopUp && live < request.getRequestedOptions()) {
            searchAndPropose(request, slots, now);
            slots = RecruitmentProposedSlot.list("requestUuid = ?1", request.getUuid());
        }
        recomputeRequestStatus(request, slots);
    }

    /** Plan and propose up to the missing option count. */
    private void searchAndPropose(RecruitmentSchedulingRequest request,
                                  List<RecruitmentProposedSlot> slots,
                                  LocalDateTime now) {
        LocalDate from = now.toLocalDate().isAfter(request.getWindowStart())
                ? now.toLocalDate()
                : request.getWindowStart();
        long live = slots.stream().filter(s -> s.getStatus().isLive()).count();
        if (from.isAfter(request.getWindowEnd())) {
            if (live == 0) {
                schedulingService.handBack(request, null, REASON_WINDOW_EXHAUSTED);
            }
            // With live options still out there, the window simply
            // yields no more — nothing to plan.
            return;
        }

        List<String> requiredEmails = mailboxesOf(request.getInterviewerUuids());
        if (requiredEmails.size() < request.getInterviewerUuids().size()) {
            log.warnf("Method B request %s: some interviewers have no mailbox — planning with %d of %d",
                    request.getUuid(), requiredEmails.size(),
                    request.getInterviewerUuids().size());
        }
        List<String> optionalEmails = mailboxesOf(request.getOptionalInterviewerUuids());
        List<MeetingRoomsResponse.MeetingRoom> rooms = calendarService.listRooms();

        List<String> mailboxes = new ArrayList<>(requiredEmails);
        mailboxes.addAll(optionalEmails);
        rooms.forEach(room -> mailboxes.add(room.emailAddress()));

        Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> schedules =
                calendarService.windowSchedules(mailboxes, from, request.getWindowEnd());
        if (schedules.isEmpty()) {
            // Graph answered nothing — blind proposals carry more trust
            // than they earned (the suggestedSlots posture). Retry later.
            deferSearch(request, now);
            return;
        }

        List<MultiSlotPlanner.PlannedSlot> alreadyPlanned = slots.stream()
                .filter(slot -> slot.getStatus().isLive())
                .map(slot -> new MultiSlotPlanner.PlannedSlot(
                        slot.getSlotStart(), slot.getSlotEnd(),
                        slot.getRoomEmail(), slot.getRoomName(), 0))
                .toList();
        List<MultiSlotPlanner.TimeInterval> excluded = slots.stream()
                .filter(slot -> !slot.getStatus().isLive())
                .map(slot -> new MultiSlotPlanner.TimeInterval(
                        slot.getSlotStart(), slot.getSlotEnd()))
                .toList();

        int needed = request.getRequestedOptions() - (int) live;
        List<MultiSlotPlanner.PlannedSlot> picks = MultiSlotPlanner.plan(
                new MultiSlotPlanner.PlanRequest(
                        from, request.getWindowEnd(),
                        request.getPermittedStart(), request.getPermittedEnd(),
                        request.getDurationMinutes(), needed,
                        request.getMinSeparationHours(), request.isDifferentDays(),
                        request.isRequireRoom(),
                        requiredEmails.size() + optionalEmails.size() + 1,
                        lowercase(requiredEmails), lowercase(optionalEmails),
                        rooms.stream()
                                .map(room -> new AvailabilitySlotSuggester.RoomOption(
                                        room.emailAddress().toLowerCase(Locale.ROOT),
                                        room.displayName(), room.capacity()))
                                .toList(),
                        schedules,
                        now.plusHours(MIN_LEAD_HOURS),
                        alreadyPlanned, excluded));
        if (picks.isEmpty()) {
            deferSearch(request, now);
            return;
        }

        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        int nextOptionNo = (int) RecruitmentProposedSlot
                .count("requestUuid = ?1", request.getUuid()) + 1;
        for (MultiSlotPlanner.PlannedSlot pick : picks) {
            RecruitmentProposedSlot slot = new RecruitmentProposedSlot();
            slot.setRequestUuid(request.getUuid());
            slot.setOptionNo(nextOptionNo++);
            slot.setSlotStart(pick.start());
            slot.setSlotEnd(pick.end());
            slot.setRoomEmail(pick.roomEmail());
            slot.setRoomName(pick.roomDisplayName());
            slot.setStatus(ProposedSlotStatus.PROPOSED);
            slot.persist();
            for (String interviewerUuid : request.getInterviewerUuids()) {
                RecruitmentSlotApproval approval = new RecruitmentSlotApproval();
                approval.setSlotUuid(slot.getUuid());
                approval.setUserUuid(interviewerUuid);
                approval.persist();
                outboxService.enqueue(request.getUuid(), slot.getUuid(),
                        SchedulingOutboxAction.SEND_PROPOSAL_DM, approval.getUuid(),
                        schedulingService.refPayload("approvalUuid", approval.getUuid()));
            }
            eventRecorder.record(RecruitmentEventBuilder
                    .event(RecruitmentEventType.SLOT_PROPOSED)
                    .application(request.getApplicationUuid())
                    .candidate(application != null ? application.getCandidateUuid() : null)
                    .position(application != null ? application.getPositionUuid() : null)
                    .actorScheduler()
                    .payload("request_uuid", request.getUuid())
                    .payload("slot_uuid", slot.getUuid())
                    .payload("option_no", slot.getOptionNo())
                    .payload("slot_start", slot.getSlotStart().toString())
                    .payload("slot_end", slot.getSlotEnd().toString())
                    .payload("room_email", slot.getRoomEmail()));
        }
    }

    private void deferSearch(RecruitmentSchedulingRequest request, LocalDateTime now) {
        request.setNextActionAt(now.plusMinutes(SEARCH_RETRY_MINUTES));
    }

    /** Derive the pipeline status from the slot set and apply it when it
     * is a legal, different transition. */
    private void recomputeRequestStatus(RecruitmentSchedulingRequest request,
                                        List<RecruitmentProposedSlot> slots) {
        long held = slots.stream().filter(slot ->
                slot.getStatus() == ProposedSlotStatus.HELD
                        || slot.getStatus() == ProposedSlotStatus.OFFERED
                        || slot.getStatus() == ProposedSlotStatus.SELECTED).count();
        long live = slots.stream().filter(slot -> slot.getStatus().isLive()).count();
        SchedulingRequestStatus desired = desiredPipelineStatus(
                (int) held, (int) live, request.getRequestedOptions());
        if (desired != request.getStatus()
                && SchedulingStateMachine.canTransition(request.getStatus(), desired)) {
            request.setStatus(desired);
        }
    }

    /**
     * The pipeline status a slot census implies (pure; DB-free tested):
     * everything secured → READY_FOR_CANDIDATE; something secured →
     * HOLDING_OPTIONS; proposals out → WAITING_FOR_INTERVIEWERS;
     * nothing alive → SEARCHING.
     */
    static SchedulingRequestStatus desiredPipelineStatus(int heldCount, int liveCount,
                                                         int requestedOptions) {
        if (heldCount >= requestedOptions) {
            return SchedulingRequestStatus.READY_FOR_CANDIDATE;
        }
        if (heldCount >= 1) {
            return SchedulingRequestStatus.HOLDING_OPTIONS;
        }
        if (liveCount >= 1) {
            return SchedulingRequestStatus.WAITING_FOR_INTERVIEWERS;
        }
        return SchedulingRequestStatus.SEARCHING;
    }

    private static List<String> mailboxesOf(List<String> userUuids) {
        List<String> emails = new ArrayList<>();
        if (userUuids == null) {
            return emails;
        }
        for (String userUuid : userUuids) {
            User user = User.findById(userUuid);
            if (user != null && user.getEmail() != null && !user.getEmail().isBlank()) {
                emails.add(user.getEmail());
            }
        }
        return emails;
    }

    private static List<String> lowercase(List<String> emails) {
        return emails.stream().map(email -> email.toLowerCase(Locale.ROOT)).toList();
    }
}
