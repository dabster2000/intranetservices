package dk.trustworks.intranet.recruitmentservice.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.api.dto.IntegrationStatusResponse;
import dk.trustworks.intranet.recruitmentservice.api.dto.IntegrationStatusResponse.OutlookStatus;
import dk.trustworks.intranet.recruitmentservice.api.dto.IntegrationStatusResponse.SlackDmStatus;
import dk.trustworks.intranet.recruitmentservice.application.RecruitmentRecordAccessService;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxStatus;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only "what happened with my interview integrations" view.
 *
 * <p>Surfaces the latest Outlook calendar outbox row + all Slack DM outbox rows
 * scoped to a single interview. Access is gated by
 * {@link RecruitmentRecordAccessService#canSeeInterview(Interview, String)} —
 * callers without record access get 404 (never 403, to avoid leaking existence).
 */
@Path("/api/recruitment/interviews/{uuid}/integrations")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class IntegrationStatusResource {

    private static final Logger LOG = Logger.getLogger(IntegrationStatusResource.class);

    @Inject RecruitmentRecordAccessService recordAccess;
    @Inject RequestHeaderHolder header;
    @Inject ObjectMapper mapper;

    @GET
    @Path("/status")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public Response get(@PathParam("uuid") String interviewUuid) {
        Interview iv = Interview.findById(interviewUuid);
        if (iv == null || !recordAccess.canSeeInterview(iv, header.getUserUuid())) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        OutlookStatus outlook = buildOutlookStatus(interviewUuid);
        List<SlackDmStatus> slackDms = buildSlackDmStatuses(interviewUuid);
        return Response.ok(new IntegrationStatusResponse(outlook, slackDms)).build();
    }

    private OutlookStatus buildOutlookStatus(String interviewUuid) {
        RecruitmentOutboxRow row = RecruitmentOutboxRow
                .<RecruitmentOutboxRow>find(
                        "relatedUuid = ?1 AND (kind = ?2 OR kind = ?3 OR kind = ?4) ORDER BY createdAt DESC",
                        interviewUuid,
                        OutboxKind.OUTLOOK_EVENT_CREATE,
                        OutboxKind.OUTLOOK_EVENT_UPDATE,
                        OutboxKind.OUTLOOK_EVENT_CANCEL)
                .firstResult();
        if (row == null) {
            return new OutlookStatus("NONE", null, null, null, 0);
        }
        String eventId = extractField(row.payloadJson, "outlookEventId");
        return new OutlookStatus(
                mapState(row.status),
                eventId,
                row.lastError,
                toInstant(row.lastAttemptAt),
                row.attemptCount);
    }

    private List<SlackDmStatus> buildSlackDmStatuses(String interviewUuid) {
        List<RecruitmentOutboxRow> rows = RecruitmentOutboxRow
                .list("relatedUuid = ?1 AND (kind = ?2 OR kind = ?3) ORDER BY createdAt DESC",
                        interviewUuid,
                        OutboxKind.SLACK_INTERVIEW_TOMORROW_DM,
                        OutboxKind.SLACK_SCORECARD_OVERDUE_DM);
        List<SlackDmStatus> out = new ArrayList<>(rows.size());
        for (RecruitmentOutboxRow row : rows) {
            out.add(new SlackDmStatus(
                    row.kind,
                    extractField(row.payloadJson, "recipientUserUuid"),
                    mapState(row.status),
                    row.lastError,
                    toInstant(row.lastAttemptAt),
                    row.attemptCount));
        }
        return out;
    }

    private static String mapState(OutboxStatus status) {
        return switch (status) {
            case DONE -> "SENT";
            case FAILED -> "FAILED";
            case PENDING, IN_FLIGHT -> "PENDING";
        };
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    private String extractField(String payloadJson, String field) {
        if (payloadJson == null) return null;
        try {
            JsonNode node = mapper.readTree(payloadJson);
            JsonNode v = node.get(field);
            return (v == null || v.isNull()) ? null : v.asText();
        } catch (Exception ex) {
            LOG.debugf("Could not parse outbox payload for field %s: %s", field, ex.getMessage());
            return null;
        }
    }
}
