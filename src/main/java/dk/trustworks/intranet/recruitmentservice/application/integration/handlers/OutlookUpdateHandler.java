package dk.trustworks.intranet.recruitmentservice.application.integration.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.OutlookUpdatePayload;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import dk.trustworks.intranet.recruitmentservice.infrastructure.OutlookCalendarException;
import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.UpdateEventCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Dispatches {@code OUTLOOK_EVENT_UPDATE} rows. No interview-side write-back
 * is required — the eventId is already known to the payload.
 */
@ApplicationScoped
public class OutlookUpdateHandler implements OutboxHandler {

    private static final Logger LOG = Logger.getLogger(OutlookUpdateHandler.class);

    @Inject OutlookCalendarPort outlook;
    @Inject ObjectMapper mapper;

    @Override
    @Transactional
    public HandlerResult handle(RecruitmentOutboxRow row) {
        try {
            OutlookUpdatePayload p = mapper.readValue(row.payloadJson, OutlookUpdatePayload.class);
            outlook.updateEvent(new UpdateEventCommand(
                    p.interviewUuid(),
                    p.organizerMailbox(),
                    p.eventId(),
                    p.startUtc(),
                    p.endUtc(),
                    p.attendeeEmails()
            ));
            return HandlerResult.ok();
        } catch (OutlookCalendarException ex) {
            return ex.isRetryable()
                    ? HandlerResult.retryable(ex.getErrorCode() + ": " + ex.getDetail())
                    : HandlerResult.terminal(ex.getErrorCode() + ": " + ex.getDetail());
        } catch (Exception ex) {
            LOG.warnf(ex, "OutlookUpdate: dispatch error row=%s", row.uuid);
            return HandlerResult.retryable("dispatch_error: " + ex.getMessage());
        }
    }
}
