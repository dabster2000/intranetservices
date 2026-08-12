package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactorDeadLetter;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactorDeadLetterService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Operator surface for the recruitment reactor dead-letter queue (V490).
 * <p>
 * A reactor that exhausts {@link RecruitmentReactor#maxDeliveryAttempts()}
 * advances its watermark past the event, so nothing will ever deliver it
 * again on its own. These three endpoints are the way back: see what was
 * dropped, re-run it once the cause is fixed, or close it out.
 * <p>
 * {@code recruitment:admin} throughout — this replays side effects (Slack
 * posts, mails) and is maintenance, not a user-facing surface. The
 * {@code RecruitmentReportsResource#rebuild} precedent.
 * <p>
 * Deliberately NO feature-flag guard: a dead letter created while a flag was
 * on must stay resolvable after it is turned off, and the reactors' own
 * handlers re-check their flags on replay anyway.
 */
@JBossLog
@Path("/recruitment/reactors")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:admin"})
public class RecruitmentReactorOpsResource {

    /** Bound on one listing — far above any plausible dead-letter backlog. */
    private static final int MAX_LIST = 200;

    @Inject
    RecruitmentReactorDeadLetterService deadLetters;

    @Inject
    Instance<RecruitmentReactor> reactors;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /** One dead letter as the ops UI sees it. */
    public record DeadLetterResponse(String reactor, long eventSeq, int attempts,
                                     String errorClass, String errorMessage, String status,
                                     LocalDateTime deadLetteredAt, LocalDateTime lastAttemptAt,
                                     LocalDateTime resolvedAt, String resolvedBy) {

        static DeadLetterResponse of(RecruitmentReactorDeadLetter d) {
            return new DeadLetterResponse(d.getReactorName(), d.getEventSeq(), d.getAttempts(),
                    d.getErrorClass(), d.getErrorMessage(), d.getStatus(),
                    d.getDeadLetteredAt(), d.getLastAttemptAt(),
                    d.getResolvedAt(), d.getResolvedBy());
        }
    }

    /** The outcome of a replay attempt. */
    public record ReplayResponse(String reactor, long eventSeq, boolean delivered, String detail) {
    }

    /**
     * Every dead letter still awaiting a human, oldest first. This is the
     * detail behind the {@code RECRUITMENT_REACTOR_DEAD_LETTER} alarm.
     */
    @GET
    @Path("/dead-letters")
    public List<DeadLetterResponse> deadLetters(@QueryParam("limit") Integer limit) {
        int bounded = limit == null ? MAX_LIST : Math.clamp(limit, 1, MAX_LIST);
        return deadLetters.listOpen(bounded).stream().map(DeadLetterResponse::of).toList();
    }

    /**
     * Re-run the reactor's handler for one dead-lettered event.
     * <p>
     * Use once the underlying cause is fixed — for the 2026-08-11 cards,
     * after the card bot was invited into the partner channel. On success
     * the row closes as {@code REPLAYED}; on failure it stays {@code OPEN}
     * with the fresh error recorded, and the caller gets a 502 carrying it.
     * <p>
     * Not idempotent in the "safe to spam" sense: each call really does
     * re-run the side effect. It is safe to <em>retry</em> a failed replay,
     * which is the case that matters.
     */
    @POST
    @Path("/dead-letters/{reactor}/{seq}/replay")
    public Response replay(@PathParam("reactor") String reactorName, @PathParam("seq") long seq) {
        RecruitmentReactorDeadLetter row = requireOpen(reactorName, seq);
        RecruitmentReactor reactor = requireReactor(reactorName);
        String actor = requestHeaderHolder.getUserUuid();
        try {
            if (!reactor.redeliver(seq)) {
                // The event is gone from the stream — nothing to deliver, so
                // the dead letter is moot rather than resolved.
                deadLetters.resolve(reactorName, seq, RecruitmentReactorDeadLetter.STATUS_ABANDONED, actor);
                log.warnf("Dead-letter replay: %s seq %d no longer exists in the stream — abandoned by %s",
                        reactorName, seq, actor);
                return Response.ok(new ReplayResponse(reactorName, seq, false,
                        "event no longer exists in the stream — closed as ABANDONED")).build();
            }
        } catch (Exception e) {
            deadLetters.recordFailedReplay(reactorName, seq, e);
            log.errorf(e, "Dead-letter replay failed: %s seq %d (attempt by %s) — left OPEN",
                    reactorName, seq, actor);
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(new ReplayResponse(reactorName, seq, false,
                            RecruitmentReactorDeadLetter.describeCause(e).errorMessage()))
                    .build();
        }
        deadLetters.resolve(reactorName, seq, RecruitmentReactorDeadLetter.STATUS_REPLAYED, actor);
        log.infof("Dead-letter replay succeeded: %s seq %d (was %d attempts) by %s",
                reactorName, seq, row.getAttempts(), actor);
        return Response.ok(new ReplayResponse(reactorName, seq, true, null)).build();
    }

    /**
     * Close a dead letter without re-delivering — the side effect is moot,
     * or a human already did it by hand. The {@code invoice_economics_uploads}
     * ABANDONED precedent (V478): the row is kept for audit, it just stops
     * holding the alarm red.
     */
    @POST
    @Path("/dead-letters/{reactor}/{seq}/abandon")
    public Response abandon(@PathParam("reactor") String reactorName, @PathParam("seq") long seq) {
        requireOpen(reactorName, seq);
        String actor = requestHeaderHolder.getUserUuid();
        deadLetters.resolve(reactorName, seq, RecruitmentReactorDeadLetter.STATUS_ABANDONED, actor);
        log.infof("Dead letter abandoned: %s seq %d by %s", reactorName, seq, actor);
        return Response.ok(new ReplayResponse(reactorName, seq, false, "closed as ABANDONED")).build();
    }

    // ------------------------------------------------------------------

    private RecruitmentReactorDeadLetter requireOpen(String reactorName, long seq) {
        RecruitmentReactorDeadLetter row = deadLetters.find(reactorName, seq);
        if (row == null) {
            throw new WebApplicationException(
                    "No dead letter for reactor " + reactorName + " seq " + seq,
                    Response.Status.NOT_FOUND);
        }
        if (!RecruitmentReactorDeadLetter.STATUS_OPEN.equals(row.getStatus())) {
            throw new WebApplicationException(
                    "Dead letter for reactor " + reactorName + " seq " + seq
                            + " is already " + row.getStatus(),
                    Response.Status.CONFLICT);
        }
        return row;
    }

    private RecruitmentReactor requireReactor(String reactorName) {
        for (RecruitmentReactor reactor : reactors) {
            if (reactor.name().equals(reactorName)) {
                return reactor;
            }
        }
        // A dead letter can outlive its reactor (renamed or removed). Say so
        // plainly instead of a 500 — the operator's option is to abandon it.
        throw new WebApplicationException(
                "No reactor named " + reactorName + " is deployed — abandon the dead letter instead",
                Response.Status.NOT_FOUND);
    }
}
