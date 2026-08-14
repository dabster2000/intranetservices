package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomsResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCalendarHold;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.model.enums.CalendarHoldOwnerKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.CalendarHoldStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SlotApprovalStatus;
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

    @Inject
    RecruitmentSchedulingCandidateService candidateService;

    @Inject
    RecruitmentInterviewService interviewService;

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

        // The two-week anchor (spec §19.4) governs the SEARCH. FINALIZING
        // is exempt (a running saga finishes), and so is
        // WAITING_FOR_CANDIDATE: once options are OUT, the candidate
        // deadline (= batch expiry) governs — yanking a live link out from
        // under the candidate would be worse than a few extra days.
        if (now.isAfter(request.getAutomationDeadline())
                && request.getStatus() != SchedulingRequestStatus.FINALIZING
                && request.getStatus() != SchedulingRequestStatus.WAITING_FOR_CANDIDATE) {
            schedulingService.handBack(request, null, REASON_AUTOMATION_DEADLINE);
            return;
        }

        if (request.getStatus() == SchedulingRequestStatus.DRAFT) {
            SchedulingStateMachine.require(SchedulingRequestStatus.DRAFT,
                    SchedulingRequestStatus.SEARCHING);
            request.setStatus(SchedulingRequestStatus.SEARCHING);
        }

        switch (request.getStatus()) {
            case SEARCHING, WAITING_FOR_INTERVIEWERS, HOLDING_OPTIONS, READY_FOR_CANDIDATE -> {
                pipelineStep(request, now);
                // The Phase 11 send step: review gate passed (or off) and
                // the requested count still secured ⇒ options go out.
                if (request.getStatus() == SchedulingRequestStatus.READY_FOR_CANDIDATE) {
                    candidateService.sendOptionsIfReady(request, now);
                }
            }
            case WAITING_FOR_CANDIDATE -> candidateService.expireIfOverdue(request, now);
            case FINALIZING -> finalizeStep(request, now);
            default -> {
            }
        }
    }

    /** The proposal-pipeline step: advance slot lifecycles (recheck →
     * holds → HELD, with compensation), nudge silent interviewers, top
     * up, then recompute the status. */
    private void pipelineStep(RecruitmentSchedulingRequest request, LocalDateTime now) {
        List<RecruitmentProposedSlot> slots = RecruitmentProposedSlot
                .list("requestUuid = ?1", request.getUuid());

        for (RecruitmentProposedSlot slot : slots) {
            if (slot.getStatus() == ProposedSlotStatus.APPROVED) {
                recheckAndHold(request, slot);
            } else if (slot.getStatus() == ProposedSlotStatus.RECHECKING) {
                progressRecheckingSlot(request, slot);
            }
        }
        remindSilentInterviewers(request, slots, now);

        long live = slots.stream().filter(s -> s.getStatus().isLive()).count();
        boolean canTopUp = switch (request.getStatus()) {
            case SEARCHING, WAITING_FOR_INTERVIEWERS, HOLDING_OPTIONS -> true;
            default -> false;
        };
        if (canTopUp && live < request.getRequestedOptions()) {
            searchAndPropose(request, slots, now);
        }
        recomputeRequestStatus(request,
                RecruitmentProposedSlot.list("requestUuid = ?1", request.getUuid()));
    }

    // ---- Recheck + holds (plan §9.3, D5/D12) -------------------------------

    /**
     * The last required approval landed: recheck availability via
     * calendarView — EXCLUDING this request's own hold event ids (the
     * digit string cannot attribute busyness; other requests' holds
     * correctly block) — then create the hold rows and enqueue their
     * Graph creates. Conflict ⇒ slot REJECTED and the search resumes.
     */
    private void recheckAndHold(RecruitmentSchedulingRequest request,
                                RecruitmentProposedSlot slot) {
        List<String> mailboxes = new ArrayList<>();
        Map<String, String> userByMailbox = new java.util.HashMap<>();
        for (String interviewerUuid : request.getInterviewerUuids()) {
            User user = User.findById(interviewerUuid);
            if (user != null && user.getEmail() != null && !user.getEmail().isBlank()) {
                mailboxes.add(user.getEmail());
                userByMailbox.put(user.getEmail(), interviewerUuid);
            }
        }
        if (slot.getRoomEmail() != null && !slot.getRoomEmail().isBlank()) {
            mailboxes.add(slot.getRoomEmail());
        }

        java.util.Set<String> ownHoldIds = ownHoldEventIds(request);
        try {
            for (String mailbox : mailboxes) {
                List<String> busy = calendarService.busyEventIdsInWindow(
                        mailbox, slot.getSlotStart(), slot.getSlotEnd());
                boolean conflict = busy.stream().anyMatch(id -> !ownHoldIds.contains(id));
                if (conflict) {
                    rejectSlot(request, slot, "RECHECK_CONFLICT");
                    return;
                }
            }
        } catch (Exception e) {
            // Graph unreachable — a recheck that cannot see the calendar
            // must not pass OR reject. Try again next sweep.
            log.warnf("Method B recheck deferred for slot %s: %s",
                    slot.getUuid(), e.getMessage());
            return;
        }

        for (String mailbox : mailboxes) {
            ensureHold(request, slot, mailbox, userByMailbox.get(mailbox));
        }
        slot.setStatus(ProposedSlotStatus.RECHECKING);
    }

    /** Every busy id this request already owns — the recheck exclusion. */
    private java.util.Set<String> ownHoldEventIds(RecruitmentSchedulingRequest request) {
        List<RecruitmentCalendarHold> holds = RecruitmentCalendarHold.list(
                "slotUuid in (select s.uuid from RecruitmentProposedSlot s where s.requestUuid = ?1)",
                request.getUuid());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (RecruitmentCalendarHold hold : holds) {
            if (hold.getGraphEventId() != null) {
                ids.add(hold.getGraphEventId());
            }
        }
        return ids;
    }

    /** Create the hold row + its CREATE_HOLD action, once. */
    private void ensureHold(RecruitmentSchedulingRequest request,
                            RecruitmentProposedSlot slot,
                            String mailbox, String userUuid) {
        long existing = RecruitmentCalendarHold.count(
                "slotUuid = ?1 and mailbox = ?2 and status != ?3",
                slot.getUuid(), mailbox, CalendarHoldStatus.RELEASED);
        if (existing > 0) {
            return;
        }
        RecruitmentCalendarHold hold = new RecruitmentCalendarHold();
        hold.setSlotUuid(slot.getUuid());
        hold.setOwnerKind(userUuid != null
                ? CalendarHoldOwnerKind.USER : CalendarHoldOwnerKind.ROOM);
        hold.setUserUuid(userUuid);
        hold.setMailbox(mailbox);
        hold.persist();
        outboxService.enqueue(request.getUuid(), slot.getUuid(),
                SchedulingOutboxAction.CREATE_HOLD, hold.getUuid(),
                schedulingService.refPayload("holdUuid", hold.getUuid()));
    }

    /**
     * A RECHECKING slot waits for its CREATE_HOLD actions: all created ⇒
     * HELD; any dead-lettered ⇒ compensate — delete what was created,
     * reject the slot, keep searching (spec §20).
     */
    private void progressRecheckingSlot(RecruitmentSchedulingRequest request,
                                        RecruitmentProposedSlot slot) {
        long failed = RecruitmentSchedulingOutbox.count(
                "slotUuid = ?1 and action = ?2 and status = ?3",
                slot.getUuid(), SchedulingOutboxAction.CREATE_HOLD,
                SchedulingOutboxStatus.FAILED);
        if (failed > 0) {
            rejectSlot(request, slot, "HOLD_FAILURE");
            return;
        }
        long pendingHolds = RecruitmentCalendarHold.count(
                "slotUuid = ?1 and status != ?2 and graphEventId is null",
                slot.getUuid(), CalendarHoldStatus.RELEASED);
        if (pendingHolds == 0) {
            slot.setStatus(ProposedSlotStatus.HELD);
        }
    }

    /** Reject + compensate one slot, with its audit event. */
    private void rejectSlot(RecruitmentSchedulingRequest request,
                            RecruitmentProposedSlot slot, String reason) {
        slot.setStatus(ProposedSlotStatus.REJECTED);
        slot.setRejectReason(reason);
        schedulingService.releaseHolds(request, slot);
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.SLOT_REJECTED)
                .application(request.getApplicationUuid())
                .candidate(application != null ? application.getCandidateUuid() : null)
                .position(application != null ? application.getPositionUuid() : null)
                .actorScheduler()
                .payload("request_uuid", request.getUuid())
                .payload("slot_uuid", slot.getUuid())
                .payload("reject_reason", reason));
    }

    // ---- Interviewer-silence reminders (defaults §29.16) -------------------

    /** First nudge after this much silence. */
    static final int NUDGE_1_HOURS = 24;
    /** Second nudge. */
    static final int NUDGE_2_HOURS = 72;
    /** Escalation to the recruiter. */
    static final int ESCALATE_HOURS = 96;

    private void remindSilentInterviewers(RecruitmentSchedulingRequest request,
                                          List<RecruitmentProposedSlot> slots,
                                          LocalDateTime now) {
        for (RecruitmentProposedSlot slot : slots) {
            if (slot.getStatus() != ProposedSlotStatus.PROPOSED
                    && slot.getStatus() != ProposedSlotStatus.PARTIALLY_APPROVED) {
                continue;
            }
            List<RecruitmentSlotApproval> approvals =
                    RecruitmentSlotApproval.list("slotUuid = ?1", slot.getUuid());
            for (RecruitmentSlotApproval approval : approvals) {
                if (approval.getStatus() != SlotApprovalStatus.PENDING) {
                    continue;
                }
                int tier = reminderTier(approval.getCreatedAt(),
                        approval.getNudgeCount(), now);
                if (tier == 0) {
                    continue;
                }
                if (tier <= 2) {
                    outboxService.enqueue(request.getUuid(), slot.getUuid(),
                            SchedulingOutboxAction.SEND_PROPOSAL_DM,
                            approval.getUuid() + ":nudge" + tier,
                            nudgePayload(approval.getUuid(), tier));
                } else {
                    outboxService.enqueue(request.getUuid(), slot.getUuid(),
                            SchedulingOutboxAction.SEND_RECRUITER_DM,
                            "ESCALATION:" + approval.getUuid(),
                            escalationPayload(approval.getUuid()));
                }
                approval.setNudgeCount(tier);
                approval.setLastNudgedAt(now);
                RecruitmentApplication application =
                        RecruitmentApplication.findById(request.getApplicationUuid());
                eventRecorder.record(RecruitmentEventBuilder
                        .event(RecruitmentEventType.SCHEDULING_REMINDER_SENT)
                        .application(request.getApplicationUuid())
                        .candidate(application != null ? application.getCandidateUuid() : null)
                        .position(application != null ? application.getPositionUuid() : null)
                        .actorScheduler()
                        .payload("request_uuid", request.getUuid())
                        .payload("slot_uuid", slot.getUuid())
                        .payload("approval_uuid", approval.getUuid())
                        .payload("interviewer_uuid", approval.getUserUuid())
                        .payload("tier", tier));
            }
        }
    }

    /**
     * The reminder tier now due for a silent approval (pure; DB-free
     * tested): 1 after 24 h, 2 after 72 h, 3 (recruiter escalation)
     * after 96 h — each fired once, tracked by {@code nudgeCount}.
     */
    static int reminderTier(LocalDateTime approvalCreatedAt, int nudgeCount,
                            LocalDateTime now) {
        if (approvalCreatedAt == null) {
            return 0;
        }
        long hours = java.time.Duration.between(approvalCreatedAt, now).toHours();
        if (nudgeCount < 1 && hours >= NUDGE_1_HOURS) {
            return 1;
        }
        if (nudgeCount == 1 && hours >= NUDGE_2_HOURS) {
            return 2;
        }
        if (nudgeCount == 2 && hours >= ESCALATE_HOURS) {
            return 3;
        }
        return 0;
    }

    private String nudgePayload(String approvalUuid, int tier) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("approvalUuid", approvalUuid, "nudge", tier));
        } catch (Exception e) {
            return "{\"approvalUuid\":\"" + approvalUuid + "\",\"nudge\":" + tier + "}";
        }
    }

    private String escalationPayload(String approvalUuid) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("notice", "SILENCE_ESCALATION",
                            "approvalUuid", approvalUuid));
        } catch (Exception e) {
            return "{\"notice\":\"SILENCE_ESCALATION\",\"approvalUuid\":\""
                    + approvalUuid + "\"}";
        }
    }

    // ---- Hold reconciliation + expiry sweep (plan §9.4) --------------------

    /** Holds examined per reconciliation sweep. */
    static final int RECONCILE_BATCH = 100;

    @Scheduled(every = "15m", identity = "recruitment-scheduling-hold-reconcile",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcileTimer() {
        if (!methodBFlag.isMethodBEnabled() || !calendarService.isEnabled()) {
            return;
        }
        reconcileHolds();
        expireOverdueSlots();
    }

    /**
     * Poll every ACTIVE hold's Graph event (no webhooks — plan §9.4):
     * still there ⇒ VERIFIED; 404 ⇒ the owner deleted it by hand ⇒
     * MISSING + re-evaluate the slot — re-create the hold if the time is
     * still free, else invalidate the option and let the advance sweep
     * re-search.
     */
    public void reconcileHolds() {
        List<String> holdUuids = QuarkusTransaction.requiringNew().call(() ->
                RecruitmentCalendarHold.<RecruitmentCalendarHold>find(
                                "graphEventId is not null and status in ?1 and slotUuid in "
                                        + "(select s.uuid from RecruitmentProposedSlot s where s.status in ?2)",
                                List.of(CalendarHoldStatus.CREATED, CalendarHoldStatus.VERIFIED),
                                List.of(ProposedSlotStatus.HELD, ProposedSlotStatus.OFFERED,
                                        ProposedSlotStatus.SELECTED))
                        .page(0, RECONCILE_BATCH)
                        .list().stream().map(RecruitmentCalendarHold::getUuid).toList());
        for (String holdUuid : holdUuids) {
            try {
                reconcileOne(holdUuid);
            } catch (Exception e) {
                log.warnf("Method B hold reconciliation failed for %s: %s",
                        holdUuid, e.getMessage());
            }
        }
    }

    private void reconcileOne(String holdUuid) {
        RecruitmentCalendarHold probe = QuarkusTransaction.requiringNew()
                .call(() -> RecruitmentCalendarHold.findById(holdUuid));
        if (probe == null || probe.getGraphEventId() == null) {
            return;
        }
        boolean exists = calendarService.holdEventExists(
                probe.getMailbox(), probe.getGraphEventId());
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentCalendarHold hold = RecruitmentCalendarHold.findById(holdUuid);
            if (hold == null || hold.getStatus() == CalendarHoldStatus.RELEASED) {
                return;
            }
            if (exists) {
                hold.setStatus(CalendarHoldStatus.VERIFIED);
                hold.setLastVerifiedAt(LocalDateTime.now());
                return;
            }
            hold.setStatus(CalendarHoldStatus.MISSING);
            RecruitmentProposedSlot slot = RecruitmentProposedSlot.findById(hold.getSlotUuid());
            RecruitmentSchedulingRequest request = slot == null ? null
                    : RecruitmentSchedulingRequest.findById(slot.getRequestUuid());
            RecruitmentApplication application = request == null ? null
                    : RecruitmentApplication.findById(request.getApplicationUuid());
            eventRecorder.record(RecruitmentEventBuilder
                    .event(RecruitmentEventType.HOLD_MISSING)
                    .application(request != null ? request.getApplicationUuid() : null)
                    .candidate(application != null ? application.getCandidateUuid() : null)
                    .position(application != null ? application.getPositionUuid() : null)
                    .actorScheduler()
                    .payload("request_uuid", request != null ? request.getUuid() : null)
                    .payload("slot_uuid", hold.getSlotUuid())
                    .payload("hold_uuid", hold.getUuid())
                    .payload("owner_kind", hold.getOwnerKind().name()));
            if (slot == null || request == null || !slot.getStatus().isLive()) {
                return;
            }
            reevaluateLostHold(request, slot, hold);
        });
    }

    /**
     * A hold vanished under a live slot: if the time is still free
     * (excluding our own holds), re-create it and the slot stays valid;
     * otherwise the owner reclaimed the time — invalidate the option.
     */
    private void reevaluateLostHold(RecruitmentSchedulingRequest request,
                                    RecruitmentProposedSlot slot,
                                    RecruitmentCalendarHold lost) {
        try {
            java.util.Set<String> ownIds = ownHoldEventIds(request);
            List<String> busy = calendarService.busyEventIdsInWindow(
                    lost.getMailbox(), slot.getSlotStart(), slot.getSlotEnd());
            boolean conflict = busy.stream().anyMatch(id -> !ownIds.contains(id));
            if (conflict) {
                rejectSlot(request, slot, "HOLD_LOST");
            } else {
                ensureHold(request, slot, lost.getMailbox(), lost.getUserUuid());
                // Back through the hold-creation wait state.
                slot.setStatus(ProposedSlotStatus.RECHECKING);
            }
            request.setNextActionAt(null);
        } catch (Exception e) {
            // Recheck unavailable — leave MISSING standing; the next
            // reconciliation pass re-evaluates.
            log.warnf("Method B lost-hold re-evaluation deferred for slot %s: %s",
                    slot.getUuid(), e.getMessage());
        }
    }

    /** Offered/held slots past their expiry are released (plan §9.4). */
    void expireOverdueSlots() {
        LocalDateTime now = LocalDateTime.now();
        QuarkusTransaction.requiringNew().run(() -> {
            List<RecruitmentProposedSlot> overdue = RecruitmentProposedSlot.list(
                    "expiresAt is not null and expiresAt < ?1 and status in ?2",
                    now, List.of(ProposedSlotStatus.HELD, ProposedSlotStatus.OFFERED));
            for (RecruitmentProposedSlot slot : overdue) {
                RecruitmentSchedulingRequest request =
                        RecruitmentSchedulingRequest.findById(slot.getRequestUuid());
                if (request == null) {
                    continue;
                }
                slot.setStatus(ProposedSlotStatus.EXPIRED);
                schedulingService.releaseHolds(request, slot);
                request.setNextActionAt(null);
            }
        });
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
                        slot.getRoomEmail(), slot.getRoomName(), 0, 0))
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
                        alreadyPlanned, excluded,
                        externalConstraintsByMailbox(request, now)));
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

    // ---- Confirmed external evidence → planner constraints (Phase 12) ------

    /**
     * Load the request's CONFIRMED, unexpired evidence and resolve it
     * into the planner's per-mailbox constraint map (plan §12.5). The
     * covered-range/expiry/merge rules live in
     * {@link AvailabilityConstraintResolver}; this only re-keys
     * userUuid → lowercase mailbox.
     */
    private Map<String, List<MultiSlotPlanner.ExternalConstraint>>
            externalConstraintsByMailbox(RecruitmentSchedulingRequest request,
                                         LocalDateTime now) {
        List<dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence>
                evidence = dk.trustworks.intranet.recruitmentservice.model
                        .RecruitmentAvailabilityEvidence.list(
                                "requestUuid = ?1 and confirmationStatus = ?2",
                                request.getUuid(),
                                dk.trustworks.intranet.recruitmentservice.model.enums
                                        .EvidenceConfirmationStatus.CONFIRMED);
        if (evidence.isEmpty()) {
            return Map.of();
        }
        Map<String, List<dk.trustworks.intranet.recruitmentservice.model
                .RecruitmentAvailabilityConstraint>> constraintsByEvidence
                = new java.util.HashMap<>();
        for (var row : evidence) {
            constraintsByEvidence.put(row.getUuid(),
                    dk.trustworks.intranet.recruitmentservice.model
                            .RecruitmentAvailabilityConstraint.list(
                                    "evidenceUuid = ?1", row.getUuid()));
        }
        Map<String, List<MultiSlotPlanner.ExternalConstraint>> byUser =
                AvailabilityConstraintResolver.resolve(evidence, constraintsByEvidence, now);
        Map<String, List<MultiSlotPlanner.ExternalConstraint>> byMailbox
                = new java.util.HashMap<>();
        byUser.forEach((userUuid, constraints) -> {
            User user = User.findById(userUuid);
            if (user != null && user.getEmail() != null && !user.getEmail().isBlank()) {
                byMailbox.put(user.getEmail().toLowerCase(Locale.ROOT), constraints);
            }
        });
        return byMailbox;
    }

    // ---- Evidence lifecycle sweep (Phase 12; spec §23 + the 48 h rule) -----

    @Scheduled(every = "15m", identity = "recruitment-scheduling-evidence-expiry",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void evidenceExpiryTimer() {
        if (!methodBFlag.isMethodBEnabled()) {
            return;
        }
        expireStaleEvidence();
    }

    /**
     * Two deterministic flips, each with its audit event: CONFIRMED past
     * its covered period → EXPIRED (the engine already ignores it by
     * timestamp — this makes the panel say so); PENDING unanswered for
     * 48 h → EXPIRED (a summary nobody confirmed must never linger
     * confirmable, D9 — and Phase 13's image-deletion timeout rides the
     * same flip).
     */
    public void expireStaleEvidence() {
        LocalDateTime now = LocalDateTime.now();
        QuarkusTransaction.requiringNew().run(() -> {
            List<dk.trustworks.intranet.recruitmentservice.model
                    .RecruitmentAvailabilityEvidence> stale =
                    dk.trustworks.intranet.recruitmentservice.model
                            .RecruitmentAvailabilityEvidence.list(
                                    "(confirmationStatus = ?1 and expiresAt is not null and expiresAt <= ?3) "
                                            + "or (confirmationStatus = ?2 and createdAt <= ?4)",
                                    dk.trustworks.intranet.recruitmentservice.model.enums
                                            .EvidenceConfirmationStatus.CONFIRMED,
                                    dk.trustworks.intranet.recruitmentservice.model.enums
                                            .EvidenceConfirmationStatus.PENDING,
                                    now,
                                    now.minusHours(AvailabilityMessageService.PENDING_TIMEOUT_HOURS));
            for (var evidence : stale) {
                boolean pendingTimeout = evidence.getConfirmationStatus()
                        == dk.trustworks.intranet.recruitmentservice.model.enums
                                .EvidenceConfirmationStatus.PENDING;
                evidence.setConfirmationStatus(
                        dk.trustworks.intranet.recruitmentservice.model.enums
                                .EvidenceConfirmationStatus.EXPIRED);
                RecruitmentSchedulingRequest request =
                        RecruitmentSchedulingRequest.findById(evidence.getRequestUuid());
                RecruitmentApplication application = request == null ? null
                        : RecruitmentApplication.findById(request.getApplicationUuid());
                eventRecorder.record(RecruitmentEventBuilder
                        .event(RecruitmentEventType.AVAILABILITY_EVIDENCE_CANCELLED)
                        .application(request != null ? request.getApplicationUuid() : null)
                        .candidate(application != null ? application.getCandidateUuid() : null)
                        .position(application != null ? application.getPositionUuid() : null)
                        .actorScheduler()
                        .payload("request_uuid", evidence.getRequestUuid())
                        .payload("evidence_uuid", evidence.getUuid())
                        .payload("reason", pendingTimeout
                                ? "PENDING_TIMEOUT" : "EXPIRED"));
            }
        });
    }

    // ---- Finalization saga (plan §11.3) ------------------------------------

    /** Structural reasons of the finalization branch. */
    static final String REASON_FINALIZE_CONFLICT = "FINALIZE_RECHECK_CONFLICT";
    static final String REASON_FINALIZE_REJECTED = "FINALIZE_REJECTED";
    static final String REASON_SELECTION_LOST = "SELECTION_LOST";

    /**
     * One resumable finalization pass, inside the per-request
     * {@code REQUIRES_NEW} transaction. Idempotency anchor: a previous
     * attempt that created the interview but crashed before completing is
     * detected by the interview-existence guard, so the meeting is never
     * created twice; a Graph outage simply returns and the next sweep
     * retries.
     */
    private void finalizeStep(RecruitmentSchedulingRequest request, LocalDateTime now) {
        RecruitmentProposedSlot selected = RecruitmentProposedSlot
                .<RecruitmentProposedSlot>find("requestUuid = ?1 and status = ?2",
                        request.getUuid(), ProposedSlotStatus.SELECTED)
                .firstResult();
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        if (selected == null || application == null) {
            schedulingService.handBack(request, null, REASON_SELECTION_LOST);
            return;
        }

        dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview existing =
                dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview
                        .<dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview>find(
                                "applicationUuid = ?1 and scheduledAt = ?2 and status = ?3",
                                application.getUuid(), selected.getSlotStart(),
                                dk.trustworks.intranet.recruitmentservice.model.enums
                                        .RecruitmentInterviewStatus.SCHEDULED)
                        .firstResult();

        if (existing == null) {
            // Step 2: recheck the selected slot (calendarView minus our
            // own holds, room included) before anything irreversible.
            java.util.Set<String> ownIds = ownHoldEventIds(request);
            List<String> mailboxes = mailboxesOf(request.getInterviewerUuids());
            if (selected.getRoomEmail() != null && !selected.getRoomEmail().isBlank()) {
                mailboxes = new ArrayList<>(mailboxes);
                mailboxes.add(selected.getRoomEmail());
            }
            try {
                for (String mailbox : mailboxes) {
                    List<String> busy = calendarService.busyEventIdsInWindow(
                            mailbox, selected.getSlotStart(), selected.getSlotEnd());
                    if (busy.stream().anyMatch(id -> !ownIds.contains(id))) {
                        selectedSlotInvalid(request, selected, now);
                        return;
                    }
                }
            } catch (Exception e) {
                // Graph unreachable — neither pass nor fail; retry.
                log.warnf("Method B finalize recheck deferred for request %s: %s",
                        request.getUuid(), e.getMessage());
                return;
            }

            // Step 3: the real interview through the existing Method A
            // create path — kit DMs, scorecard SLAs, RSVP tracking and the
            // two-event model all apply unchanged. Optional interviewers
            // ride along when their calendar is free for the slot
            // (defaults §29.7 — they never blocked, now they join if they
            // can; a failed probe just leaves them off the invitation).
            List<String> attendees = new ArrayList<>(request.getInterviewerUuids());
            for (String optionalUuid : request.getOptionalInterviewerUuids()) {
                User optional = User.findById(optionalUuid);
                if (optional == null || optional.getEmail() == null
                        || optional.getEmail().isBlank()) {
                    continue;
                }
                try {
                    List<String> busy = calendarService.busyEventIdsInWindow(
                            optional.getEmail(), selected.getSlotStart(), selected.getSlotEnd());
                    if (busy.stream().allMatch(ownIds::contains)) {
                        attendees.add(optionalUuid);
                    }
                } catch (Exception e) {
                    log.debugf("Method B optional-interviewer probe failed for %s — leaving them off: %s",
                            optionalUuid, e.getMessage());
                }
            }
            dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate candidate =
                    dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate
                            .findById(application.getCandidateUuid());
            dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition position =
                    dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition
                            .findById(application.getPositionUuid());
            try {
                existing = interviewService.create(application, position, candidate,
                        new dk.trustworks.intranet.recruitmentservice.dto.InterviewCreateRequest(
                                request.getKind(), request.getRound(), attendees,
                                request.getLocation(), selected.getRoomEmail(),
                                selected.getSlotStart(), request.getDurationMinutes(),
                                request.isOnlineMeeting()),
                        java.util.UUID.fromString(request.getRecruiterUuid()));
            } catch (dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation e) {
                // A rule the pipeline could not have satisfied (terminal
                // application, stage drift) — automation cannot finish.
                log.warnf("Method B finalize rejected for request %s: %s",
                        request.getUuid(), e.getMessage());
                schedulingService.handBack(request, null, REASON_FINALIZE_REJECTED);
                return;
            }
        }

        // Spec §23's stale-evidence check, non-blocking by design: every
        // required interviewer explicitly approved THIS slot with a
        // button — a fresh direct approval outranks an expired generic
        // claim, and the live recheck above guarded actual conflicts —
        // so staleness earns a notice, never a rollback.
        enqueueStaleEvidenceNotices(request, selected, now);

        // Steps 4+5: the winner's holds are superseded by the real event,
        // the losers are released, the batch closes, the request ends.
        selected.setStatus(ProposedSlotStatus.FINALIZED);
        schedulingService.releaseHolds(request, selected);
        schedulingService.releasePipeline(request, "REQUEST_SCHEDULED");
        SchedulingStateMachine.require(request.getStatus(), SchedulingRequestStatus.SCHEDULED);
        request.setStatus(SchedulingRequestStatus.SCHEDULED);
        outboxService.enqueue(request.getUuid(), null,
                SchedulingOutboxAction.SEND_RECRUITER_DM,
                "SCHEDULED:" + request.getUuid(),
                schedulingService.refPayload("notice", "SCHEDULED"));
        // Spec §16.3's second half — the interviewers hear it in Slack
        // too, not only via the Outlook invitation, and every proposal
        // card stops claiming a provisional reservation.
        outboxService.enqueue(request.getUuid(), selected.getUuid(),
                SchedulingOutboxAction.NOTIFY_FINALIZED,
                "FINALIZED:" + selected.getUuid(),
                schedulingService.refPayload("selectedSlotUuid", selected.getUuid()));
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.SCHEDULING_FINALIZED)
                .application(request.getApplicationUuid())
                .candidate(application.getCandidateUuid())
                .position(application.getPositionUuid())
                .actorScheduler()
                .payload("request_uuid", request.getUuid())
                .payload("slot_uuid", selected.getUuid())
                .payload("interview_uuid", existing.getUuid())
                .payload("slot_start", selected.getSlotStart().toString()));
        log.infof("Method B request %s finalized: interview %s at %s",
                request.getUuid(), existing.getUuid(), selected.getSlotStart());
    }

    /**
     * Enqueue one RECONFIRM notice per interviewer whose expired-but-
     * once-confirmed AVAILABLE_ONLY evidence covered the finalized
     * slot's date (plan §12.5, spec §23). Idempotent via the outbox key
     * — a resumed finalization enqueues nothing twice.
     */
    private void enqueueStaleEvidenceNotices(RecruitmentSchedulingRequest request,
                                             RecruitmentProposedSlot selected,
                                             LocalDateTime now) {
        List<dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence>
                evidence = dk.trustworks.intranet.recruitmentservice.model
                        .RecruitmentAvailabilityEvidence.list(
                                "requestUuid = ?1", request.getUuid());
        if (evidence.isEmpty()) {
            return;
        }
        Map<String, List<dk.trustworks.intranet.recruitmentservice.model
                .RecruitmentAvailabilityConstraint>> constraintsByEvidence
                = new java.util.HashMap<>();
        for (var row : evidence) {
            constraintsByEvidence.put(row.getUuid(),
                    dk.trustworks.intranet.recruitmentservice.model
                            .RecruitmentAvailabilityConstraint.list(
                                    "evidenceUuid = ?1", row.getUuid()));
        }
        List<String> staleUsers = AvailabilityConstraintResolver.staleGatingUserUuids(
                evidence, constraintsByEvidence, selected.getSlotStart(), now);
        for (String userUuid : staleUsers) {
            // The user's newest once-confirmed evidence row anchors the
            // notice (language + the outbox idempotency qualifier).
            evidence.stream()
                    .filter(row -> row.getUserUuid().equals(userUuid)
                            && row.getConfirmedAt() != null)
                    .max(java.util.Comparator.comparing(
                            dk.trustworks.intranet.recruitmentservice.model
                                    .RecruitmentAvailabilityEvidence::getCreatedAt))
                    .ifPresent(row -> outboxService.enqueue(request.getUuid(),
                            selected.getUuid(),
                            SchedulingOutboxAction.SEND_EVIDENCE_DM,
                            "RECONFIRM:" + row.getUuid(),
                            reconfirmPayload(row.getUuid(), selected)));
        }
    }

    private String reconfirmPayload(String evidenceUuid, RecruitmentProposedSlot selected) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of(
                            "evidenceUuid", evidenceUuid,
                            "kind", "RECONFIRM",
                            "slotStart", selected.getSlotStart().toString(),
                            "slotEnd", selected.getSlotEnd().toString()));
        } catch (Exception e) {
            return "{\"evidenceUuid\":\"" + evidenceUuid + "\",\"kind\":\"RECONFIRM\","
                    + "\"slotStart\":\"" + selected.getSlotStart() + "\","
                    + "\"slotEnd\":\"" + selected.getSlotEnd() + "\"}";
        }
    }

    /**
     * The selected option died at recheck (spec §16.3): reject it, then —
     * remaining options ⇒ the candidate chooses again; window still open
     * ⇒ re-search (batch closes, the recruiter's next batch mints a fresh
     * link); otherwise hand back.
     */
    private void selectedSlotInvalid(RecruitmentSchedulingRequest request,
                                     RecruitmentProposedSlot selected,
                                     LocalDateTime now) {
        rejectSlot(request, selected, REASON_FINALIZE_CONFLICT);
        long remaining = RecruitmentProposedSlot.count(
                "requestUuid = ?1 and status = ?2 and (expiresAt is null or expiresAt > ?3)",
                request.getUuid(), ProposedSlotStatus.OFFERED, now);
        switch (afterSelectedSlotInvalid((int) remaining,
                !now.toLocalDate().isAfter(request.getWindowEnd()))) {
            case CHOOSE_AGAIN -> {
                SchedulingStateMachine.require(request.getStatus(),
                        SchedulingRequestStatus.WAITING_FOR_CANDIDATE);
                request.setStatus(SchedulingRequestStatus.WAITING_FOR_CANDIDATE);
            }
            case RESEARCH -> {
                schedulingService.releasePipeline(request, REASON_FINALIZE_CONFLICT);
                SchedulingStateMachine.require(request.getStatus(),
                        SchedulingRequestStatus.SEARCHING);
                request.setStatus(SchedulingRequestStatus.SEARCHING);
                request.setNextActionAt(null);
            }
            case HAND_BACK -> schedulingService.handBack(request, null, REASON_WINDOW_EXHAUSTED);
        }
    }

    /** The post-recheck-failure branch (pure; DB-free tested). */
    enum RecheckFailureBranch {CHOOSE_AGAIN, RESEARCH, HAND_BACK}

    static RecheckFailureBranch afterSelectedSlotInvalid(int remainingOffered,
                                                         boolean windowAllowsMore) {
        if (remainingOffered > 0) {
            return RecheckFailureBranch.CHOOSE_AGAIN;
        }
        return windowAllowsMore
                ? RecheckFailureBranch.RESEARCH
                : RecheckFailureBranch.HAND_BACK;
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
