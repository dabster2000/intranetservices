package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.api.dto.RetryRequest;
import dk.trustworks.intranet.recruitmentservice.application.RecruitmentRecordAccessService;
import dk.trustworks.intranet.recruitmentservice.application.integration.RecruitmentOutboxRetryService;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Re-arms a single FAILED Outlook outbox row for an interview. Slack DMs are
 * intentionally excluded — overdue/tomorrow DMs are time-bound and re-firing them
 * after the fact is rarely the right thing.
 *
 * <p>Returns:
 * <ul>
 *   <li>{@code 204} — row was FAILED and has been reset to PENDING</li>
 *   <li>{@code 400} — body is invalid (unsupported kind / unknown fallback /
 *       fallback used with a non-update kind)</li>
 *   <li>{@code 404} — interview not found OR caller can't see it</li>
 *   <li>{@code 409} — no FAILED row of that kind exists (nothing to retry)</li>
 * </ul>
 */
@Path("/api/recruitment/interviews/{uuid}/integrations")
@RequestScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class IntegrationRetryResource {

    private static final String FALLBACK_CANCEL_RECREATE = "cancel-recreate";

    @Inject RecruitmentRecordAccessService recordAccess;
    @Inject RecruitmentOutboxRetryService retryService;
    @Inject RequestHeaderHolder header;

    @POST
    @Path("/retry")
    @RolesAllowed({"recruitment:write"})
    public Response retry(@PathParam("uuid") String interviewUuid, @Valid RetryRequest req) {
        if (req == null || req.kind() == null) {
            throw new BadRequestException("kind is required");
        }
        validateKind(req.kind());
        validateFallback(req.kind(), req.fallback());

        Interview iv = Interview.findById(interviewUuid);
        if (iv == null || !recordAccess.canSeeInterview(iv, header.getUserUuid())) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        boolean retried = retryService.retryFailedRow(interviewUuid, req.kind());
        return retried
                ? Response.noContent().build()
                : Response.status(Response.Status.CONFLICT).build();
    }

    private static void validateKind(OutboxKind kind) {
        switch (kind) {
            case OUTLOOK_EVENT_CREATE, OUTLOOK_EVENT_UPDATE, OUTLOOK_EVENT_CANCEL -> { /* ok */ }
            default -> throw new BadRequestException(
                    "kind must be one of OUTLOOK_EVENT_CREATE / OUTLOOK_EVENT_UPDATE / OUTLOOK_EVENT_CANCEL");
        }
    }

    private static void validateFallback(OutboxKind kind, String fallback) {
        if (fallback == null) return;
        if (!FALLBACK_CANCEL_RECREATE.equals(fallback)) {
            throw new BadRequestException("fallback must be null or 'cancel-recreate'");
        }
        if (kind != OutboxKind.OUTLOOK_EVENT_UPDATE) {
            throw new BadRequestException("fallback is only valid for kind=OUTLOOK_EVENT_UPDATE");
        }
    }
}
