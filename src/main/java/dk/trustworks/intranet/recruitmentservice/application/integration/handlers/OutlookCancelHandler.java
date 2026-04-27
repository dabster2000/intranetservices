package dk.trustworks.intranet.recruitmentservice.application.integration.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.OutlookCancelPayload;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import dk.trustworks.intranet.recruitmentservice.infrastructure.OutlookCalendarException;
import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CancelEventCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Dispatches {@code OUTLOOK_EVENT_CANCEL} rows. Clears
 * {@code interview.outlookEventId} on success. A {@code GRAPH_404} terminal
 * exception is treated as success — the event is already gone, so the
 * postcondition (eventId cleared) is achieved.
 */
@ApplicationScoped
public class OutlookCancelHandler implements OutboxHandler {

    private static final Logger LOG = Logger.getLogger(OutlookCancelHandler.class);

    @Inject OutlookCalendarPort outlook;
    @Inject ObjectMapper mapper;

    @Override
    @Transactional
    public HandlerResult handle(RecruitmentOutboxRow row) {
        OutlookCancelPayload p;
        try {
            p = mapper.readValue(row.payloadJson, OutlookCancelPayload.class);
        } catch (Exception ex) {
            LOG.warnf(ex, "OutlookCancel: payload parse error row=%s", row.uuid);
            return HandlerResult.terminal("payload_parse_error: " + ex.getMessage());
        }
        try {
            outlook.cancelEvent(new CancelEventCommand(
                    p.interviewUuid(),
                    p.organizerMailbox(),
                    p.eventId(),
                    p.reason()
            ));
            clearEventId(p.interviewUuid());
            return HandlerResult.ok();
        } catch (OutlookCalendarException ex) {
            if ("GRAPH_404".equals(ex.getErrorCode())) {
                LOG.debugf("OutlookCancel: event already gone interview=%s", p.interviewUuid());
                clearEventId(p.interviewUuid());
                return HandlerResult.ok();
            }
            return ex.isRetryable()
                    ? HandlerResult.retryable(ex.getErrorCode() + ": " + ex.getDetail())
                    : HandlerResult.terminal(ex.getErrorCode() + ": " + ex.getDetail());
        } catch (Exception ex) {
            LOG.warnf(ex, "OutlookCancel: dispatch error row=%s", row.uuid);
            return HandlerResult.retryable("dispatch_error: " + ex.getMessage());
        }
    }

    private void clearEventId(String interviewUuid) {
        Interview iv = findInterview(interviewUuid);
        if (iv != null) {
            iv.outlookEventId = null;
        }
    }

    Interview findInterview(String uuid) {
        return Interview.findById(uuid);
    }
}
