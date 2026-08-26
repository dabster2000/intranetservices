package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentHrSlackNotifier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The calendar retry sweep (V533) — the durable half of the
 * candidate-invite robustness fix. Scheduling commands mark an interview
 * with {@code calendar_retry_at} when a Graph calendar write fails
 * RETRYABLY (429 / 5xx / timeout — production 2026-08-24: a Graph 504
 * dropped a candidate's only invitation and the code shrugged); this
 * sweep finishes the job after the transaction committed, surviving
 * restarts and deploys.
 * <p>
 * What needs repairing is DERIVED from the row, never stored
 * ({@link #decide}): CANCELLED → finish the deletes; no internal event →
 * recreate both events; no candidate event → create the candidate's own
 * invitation; both present → re-PATCH the candidate event (a reschedule
 * whose candidate half failed leaves the candidate holding the OLD
 * time). Replays are safe end to end: creates carry a Graph
 * {@code transactionId} (interview UUID / UUID+"-candidate"), deletes
 * treat 404 as done, and a PATCH with current facts is idempotent.
 * <p>
 * Concurrency follows the scheduling-outbox idiom: an atomic claim
 * UPDATE burns the attempt and leases the row ({@link #CLAIM_LEASE_MINUTES})
 * so the other instance skips it; Graph calls run OUTSIDE any
 * transaction; bookkeeping commits in its own short transactions.
 * Attempts cap at {@link #MAX_ATTEMPTS}; the dead-letter is a Slack
 * alert to HR plus a terminal {@code INTERVIEW_CANDIDATE_INVITE_FAILED}
 * timeline event — a person, not a WARN. An interview whose time passes
 * with the candidate still uninvited is alerted the same way rather
 * than retried into the past.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentCalendarRepairJob {

    /** Attempts before dead-letter — the scheduling-outbox cap, and with
     * the same 2,4,8,…,60-minute backoff roughly a 3-hour Graph outage. */
    public static final int MAX_ATTEMPTS = 8;

    /** A claim older than this is presumed crashed (outbox idiom). */
    static final int CLAIM_LEASE_MINUTES = 10;

    /** Rows per sweep — bounds one sweep's worst-case Graph work. */
    static final int BATCH_SIZE = 25;

    @Inject
    RecruitmentCalendarService calendarService;

    @Inject
    RecruitmentEventRecorder recorder;

    @Inject
    RecruitmentHrSlackNotifier hrNotifier;

    @Inject
    EntityManager em;

    /** When the next attempt is due: the outbox backoff (2,4,8,…,60 min). */
    public static LocalDateTime nextAttemptAt(int attempts, LocalDateTime now) {
        return now.plusMinutes(SchedulingOutboxService.backoffMinutes(attempts));
    }

    @Scheduled(every = "5m", identity = "recruitment-calendar-repair",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweepTimer() {
        if (!calendarService.isEnabled()) {
            return;
        }
        try {
            sweep();
        } catch (RuntimeException e) {
            log.errorf(e, "Calendar repair sweep failed");
        }
    }

    /** One sweep. Public for tests and manual ops runs. */
    public int sweep() {
        LocalDateTime now = LocalDateTime.now();
        List<String> due = QuarkusTransaction.requiringNew().call(() ->
                RecruitmentInterview.<RecruitmentInterview>find(
                                "calendarRetryAt is not null and calendarRetryAt <= ?1 "
                                        + "order by calendarRetryAt", now)
                        .page(0, BATCH_SIZE)
                        .list().stream().map(RecruitmentInterview::getUuid).toList());
        int repaired = 0;
        for (String uuid : due) {
            if (repairOne(uuid, now)) {
                repaired++;
            }
        }
        if (!due.isEmpty()) {
            log.infof("Calendar repair sweep: %d due, %d claimed and attempted", due.size(), repaired);
        }
        return repaired;
    }

    /** @return true when this instance claimed and attempted the row. */
    private boolean repairOne(String uuid, LocalDateTime now) {
        if (!QuarkusTransaction.requiringNew().call(() -> claim(uuid, now))) {
            return false; // another instance owns it, or it resolved meanwhile
        }
        RepairContext ctx = QuarkusTransaction.requiringNew().call(() -> loadContext(uuid));
        if (ctx == null) {
            // Row (or its application chain) is gone — nothing to repair.
            QuarkusTransaction.requiringNew().run(() -> clearMarker(uuid, null));
            return true;
        }
        int attempts = ctx.interview().getCalendarRetryAttempts();
        RepairAction action = decide(ctx.interview().getStatus(),
                ctx.interview().getGraphEventId(),
                ctx.interview().getGraphCandidateEventId(),
                ctx.candidate() != null && ctx.candidate().getEmail() != null
                        && !ctx.candidate().getEmail().isBlank(),
                ctx.interview().getScheduledAt(), now);
        if (attempts > MAX_ATTEMPTS && action != RepairAction.DROP_MARKER
                && action != RepairAction.ABANDON_PAST) {
            deadLetter(ctx, action, "retry cap reached (" + MAX_ATTEMPTS + " attempts): "
                    + ctx.interview().getCalendarRetryLastError(), null);
            return true;
        }
        switch (action) {
            case DROP_MARKER -> QuarkusTransaction.requiringNew()
                    .run(() -> clearMarker(uuid, null));
            case ABANDON_PAST -> abandonPast(ctx);
            case DELETE_EVENTS -> deleteEvents(ctx, now);
            case CREATE_ALL -> createAll(ctx, now);
            case CREATE_CANDIDATE -> createCandidate(ctx, now);
            case PATCH_CANDIDATE -> patchCandidate(ctx, now);
        }
        return true;
    }

    // ---- The decision matrix (pure, DB-free tested) ------------------------

    /** What one marked row needs. */
    enum RepairAction {
        /** Nothing to do — clear the marker silently. */
        DROP_MARKER,
        /** CANCELLED with events still standing — finish the deletes. */
        DELETE_EVENTS,
        /** The interview time passed unrepaired — stop, and alert when the
         * candidate never got their invitation. */
        ABANDON_PAST,
        /** No internal event — recreate the full two-event split. */
        CREATE_ALL,
        /** Internal event stands, candidate's own event missing. */
        CREATE_CANDIDATE,
        /** Both events stand — the candidate event content is stale. */
        PATCH_CANDIDATE
    }

    static RepairAction decide(RecruitmentInterviewStatus status,
                               String graphEventId,
                               String graphCandidateEventId,
                               boolean candidateHasEmail,
                               LocalDateTime scheduledAt,
                               LocalDateTime now) {
        if (status == RecruitmentInterviewStatus.CANCELLED) {
            return graphEventId != null || graphCandidateEventId != null
                    ? RepairAction.DELETE_EVENTS
                    : RepairAction.DROP_MARKER;
        }
        if (scheduledAt == null || !scheduledAt.isAfter(now)) {
            return RepairAction.ABANDON_PAST;
        }
        if (graphEventId == null) {
            return RepairAction.CREATE_ALL;
        }
        if (graphCandidateEventId == null) {
            // Email removed since the failure ⇒ no invitation is intended
            // any more — the internal event's body states that plainly.
            return candidateHasEmail ? RepairAction.CREATE_CANDIDATE : RepairAction.DROP_MARKER;
        }
        return RepairAction.PATCH_CANDIDATE;
    }

    // ---- Actions -----------------------------------------------------------

    private void createAll(RepairContext ctx, LocalDateTime now) {
        RecruitmentCalendarService.CreateResult result = calendarService.createEvent(
                ctx.interview(), ctx.candidate(), ctx.position());
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentInterview managed = RecruitmentInterview.findById(ctx.interview().getUuid());
            if (managed == null) {
                return;
            }
            if (result.created() != null) {
                managed.setGraphEventId(result.created().eventId());
                managed.setGraphOrganizer(result.created().organizer());
                managed.setJoinUrl(result.created().joinUrl());
                managed.setGraphCandidateEventId(result.created().candidateEventId());
            }
        });
        if (result.created() != null && result.created().candidateEventId() != null) {
            succeed(ctx, "CREATED");
        } else if (result.created() != null && result.candidateFailure() == null) {
            // Internal event restored; no candidate invitation intended.
            QuarkusTransaction.requiringNew().run(() -> clearMarker(ctx.interview().getUuid(), null));
        } else {
            RecruitmentCalendarService.GraphWriteFailure failure =
                    result.internalFailure() != null ? result.internalFailure()
                            : result.candidateFailure();
            if (failure == null) {
                // Toggle off / organizer unresolvable — retrying is pointless.
                QuarkusTransaction.requiringNew().run(() -> clearMarker(ctx.interview().getUuid(), null));
            } else {
                fail(ctx, failure, now);
            }
        }
    }

    private void createCandidate(RepairContext ctx, LocalDateTime now) {
        RecruitmentCalendarService.CandidateEventOutcome outcome =
                calendarService.createCandidateEventFor(ctx.interview(), ctx.candidate(),
                        ctx.position());
        if (outcome.candidateEventId() != null) {
            QuarkusTransaction.requiringNew().run(() -> {
                RecruitmentInterview managed =
                        RecruitmentInterview.findById(ctx.interview().getUuid());
                if (managed != null) {
                    managed.setGraphCandidateEventId(outcome.candidateEventId());
                }
            });
            succeed(ctx, "CREATED");
        } else if (outcome.failure() != null) {
            fail(ctx, outcome.failure(), now);
        } else {
            QuarkusTransaction.requiringNew().run(() -> clearMarker(ctx.interview().getUuid(), null));
        }
    }

    private void patchCandidate(RepairContext ctx, LocalDateTime now) {
        RecruitmentCalendarService.GraphWriteFailure failure =
                calendarService.patchCandidateEvent(ctx.interview(), ctx.candidate(),
                        ctx.position());
        if (failure == null) {
            succeed(ctx, "UPDATED");
        } else {
            fail(ctx, failure, now);
        }
    }

    private void deleteEvents(RepairContext ctx, LocalDateTime now) {
        RecruitmentCalendarService.CancelResult result =
                calendarService.cancelEvent(ctx.interview());
        if (result.allDeleted()) {
            QuarkusTransaction.requiringNew().run(() -> clearMarker(ctx.interview().getUuid(), null));
        } else if (result.failure().retryable()
                && ctx.interview().getCalendarRetryAttempts() < MAX_ATTEMPTS) {
            backoff(ctx, result.failure(), now);
        } else {
            // Dead-letter the delete: a live invitation to a cancelled
            // interview is candidate-facing damage a person must fix. No
            // INVITE_FAILED event — the invitation exists; its cancellation
            // is what failed, and INTERVIEW_CANCELLED is already on record.
            QuarkusTransaction.requiringNew().run(() ->
                    clearMarker(ctx.interview().getUuid(), result.failure().message()));
            if (ctx.interview().getGraphCandidateEventId() != null) {
                hrNotifier.notifyCandidateInviteFailed(ctx.candidate(), ctx.interview(),
                        "The interview was cancelled but the candidate's Outlook invitation "
                                + "could NOT be cancelled — they still hold a live invite. "
                                + "Cancel it by hand from the " + ctx.interview().getGraphOrganizer()
                                + " calendar, or tell them directly.",
                        result.failure().message(), result.failure().graphRequestId());
            }
        }
    }

    private void abandonPast(RepairContext ctx) {
        boolean inviteNeverSent = ctx.interview().getGraphCandidateEventId() == null
                && ctx.candidate() != null && ctx.candidate().getEmail() != null
                && !ctx.candidate().getEmail().isBlank();
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentInterview managed = RecruitmentInterview.findById(ctx.interview().getUuid());
            if (managed == null) {
                return;
            }
            managed.setCalendarRetryAt(null);
            if (inviteNeverSent) {
                recorder.record(RecruitmentInterviewService.interviewEvent(
                                RecruitmentEventType.INTERVIEW_CANDIDATE_INVITE_FAILED,
                                managed, ctx.application(), ctx.position())
                        .actorSystem()
                        .payload("reason", "interview time passed before the invitation "
                                + "could be delivered: " + managed.getCalendarRetryLastError())
                        .payload("attempts", managed.getCalendarRetryAttempts())
                        .payload("graph_request_id", null));
            }
        });
        if (inviteNeverSent) {
            hrNotifier.notifyCandidateInviteFailed(ctx.candidate(), ctx.interview(),
                    "The interview time has passed and the candidate's Outlook invitation "
                            + "was never delivered (Graph kept failing). Make sure the "
                            + "candidate actually knew about the meeting, and follow up "
                            + "with them directly.",
                    ctx.interview().getCalendarRetryLastError(), null);
        }
    }

    // ---- Bookkeeping -------------------------------------------------------

    /** Success: persist the win, clear the marker, tell the timeline. */
    private void succeed(RepairContext ctx, String inviteKind) {
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentInterview managed = RecruitmentInterview.findById(ctx.interview().getUuid());
            if (managed == null) {
                return;
            }
            managed.setCalendarRetryAt(null);
            managed.setCalendarRetryLastError(null);
            recorder.record(RecruitmentInterviewService.interviewEvent(
                            RecruitmentEventType.INTERVIEW_CANDIDATE_INVITE_SENT,
                            managed, ctx.application(), ctx.position())
                    .actorSystem()
                    .payload("invite_kind", inviteKind)
                    .payload("scheduled_at", managed.getScheduledAt() != null
                            ? managed.getScheduledAt().toString() : null));
        });
        log.infof("Calendar repair: candidate invite %s for interview %s (attempt %d)",
                inviteKind, ctx.interview().getUuid(), ctx.interview().getCalendarRetryAttempts());
    }

    /** Failure: backoff while retryable and under the cap, else dead-letter. */
    private void fail(RepairContext ctx, RecruitmentCalendarService.GraphWriteFailure failure,
                      LocalDateTime now) {
        if (failure.retryable() && ctx.interview().getCalendarRetryAttempts() < MAX_ATTEMPTS) {
            backoff(ctx, failure, now);
            return;
        }
        deadLetter(ctx, decideForLogging(ctx), failure.message(), failure.graphRequestId());
    }

    private RepairAction decideForLogging(RepairContext ctx) {
        return decide(ctx.interview().getStatus(), ctx.interview().getGraphEventId(),
                ctx.interview().getGraphCandidateEventId(),
                ctx.candidate() != null && ctx.candidate().getEmail() != null
                        && !ctx.candidate().getEmail().isBlank(),
                ctx.interview().getScheduledAt(), LocalDateTime.now());
    }

    private void backoff(RepairContext ctx, RecruitmentCalendarService.GraphWriteFailure failure,
                         LocalDateTime now) {
        int attempts = ctx.interview().getCalendarRetryAttempts();
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentInterview managed = RecruitmentInterview.findById(ctx.interview().getUuid());
            if (managed == null) {
                return;
            }
            managed.setCalendarRetryAt(nextAttemptAt(attempts, now));
            managed.setCalendarRetryLastError(failure.message());
        });
        log.warnf("Calendar repair attempt %d failed for interview %s — will retry: %s",
                attempts, ctx.interview().getUuid(), failure.message());
    }

    /** Terminal: clear the marker, put the fact on the timeline, alert HR. */
    private void deadLetter(RepairContext ctx, RepairAction action, String reason,
                            String graphRequestId) {
        boolean candidateFacing = ctx.candidate() != null
                && ctx.candidate().getEmail() != null && !ctx.candidate().getEmail().isBlank()
                && action != RepairAction.DELETE_EVENTS;
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentInterview managed = RecruitmentInterview.findById(ctx.interview().getUuid());
            if (managed == null) {
                return;
            }
            managed.setCalendarRetryAt(null);
            managed.setCalendarRetryLastError(reason);
            if (candidateFacing) {
                recorder.record(RecruitmentInterviewService.interviewEvent(
                                RecruitmentEventType.INTERVIEW_CANDIDATE_INVITE_FAILED,
                                managed, ctx.application(), ctx.position())
                        .actorSystem()
                        .payload("reason", reason)
                        .payload("attempts", managed.getCalendarRetryAttempts())
                        .payload("graph_request_id", graphRequestId));
            }
        });
        log.errorf("Calendar repair DEAD-LETTERED for interview %s after %d attempts (%s): %s",
                ctx.interview().getUuid(), ctx.interview().getCalendarRetryAttempts(),
                action, reason);
        if (candidateFacing) {
            hrNotifier.notifyCandidateInviteFailed(ctx.candidate(), ctx.interview(),
                    "The candidate's Outlook invitation could NOT be delivered — automation "
                            + "has given up after " + ctx.interview().getCalendarRetryAttempts()
                            + " attempts. Invite them by hand, or fix the cause and re-save "
                            + "the interview.",
                    reason, graphRequestId);
        }
    }

    /**
     * The atomic claim (outbox idiom): burn the attempt and lease the row
     * by pushing {@code calendar_retry_at} forward, in one UPDATE — the
     * losing instance affects zero rows. A crash mid-attempt re-offers the
     * row when the lease expires; Graph-side idempotency absorbs the replay.
     */
    private boolean claim(String uuid, LocalDateTime now) {
        return em.createNativeQuery(
                        "UPDATE recruitment_interviews "
                                + "SET calendar_retry_attempts = calendar_retry_attempts + 1, "
                                + "    calendar_retry_at = :lease "
                                + "WHERE uuid = :uuid "
                                + "  AND calendar_retry_at IS NOT NULL "
                                + "  AND calendar_retry_at <= :now")
                .setParameter("uuid", uuid)
                .setParameter("lease", now.plusMinutes(CLAIM_LEASE_MINUTES))
                .setParameter("now", now)
                .executeUpdate() == 1;
    }

    private void clearMarker(String uuid, String lastError) {
        RecruitmentInterview managed = RecruitmentInterview.findById(uuid);
        if (managed == null) {
            return;
        }
        managed.setCalendarRetryAt(null);
        if (lastError != null) {
            managed.setCalendarRetryLastError(lastError);
        }
    }

    record RepairContext(RecruitmentInterview interview,
                         RecruitmentApplication application,
                         RecruitmentPosition position,
                         RecruitmentCandidate candidate) { }

    /** Fresh post-claim state; null when the row or its chain is gone. */
    private RepairContext loadContext(String uuid) {
        RecruitmentInterview interview = RecruitmentInterview.findById(uuid);
        if (interview == null) {
            return null;
        }
        em.detach(interview); // read outside the TX; mutations reload managed
        RecruitmentApplication application =
                RecruitmentApplication.findById(interview.getApplicationUuid());
        if (application == null) {
            return null;
        }
        RecruitmentPosition position =
                RecruitmentPosition.findById(application.getPositionUuid());
        if (position == null) {
            return null;
        }
        RecruitmentCandidate candidate =
                RecruitmentCandidate.findById(application.getCandidateUuid());
        return new RepairContext(interview, application, position, candidate);
    }
}
