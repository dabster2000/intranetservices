package dk.trustworks.intranet.recruitmentservice.application.integration.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.OutlookCreatePayload;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import dk.trustworks.intranet.recruitmentservice.infrastructure.OutlookCalendarException;
import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CreateEventCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Dispatches {@code OUTLOOK_EVENT_CREATE} rows by calling
 * {@link OutlookCalendarPort#createEvent(CreateEventCommand)} and writing the
 * resulting Graph eventId back onto the {@link Interview} row.
 *
 * <p>{@link #findInterview(String)} is package-private so unit tests can
 * override it without static-mocking Panache (which Mockito does not support
 * for parent-class statics).
 */
@ApplicationScoped
public class OutlookCreateHandler implements OutboxHandler {

    private static final Logger LOG = Logger.getLogger(OutlookCreateHandler.class);

    @Inject OutlookCalendarPort outlook;
    @Inject ObjectMapper mapper;

    @Override
    @Transactional
    public HandlerResult handle(RecruitmentOutboxRow row) {
        try {
            OutlookCreatePayload p = mapper.readValue(row.payloadJson, OutlookCreatePayload.class);
            Interview iv = findInterview(p.interviewUuid());
            if (iv == null || iv.status == InterviewStatus.CANCELLED) {
                LOG.debugf("OutlookCreate: skip — interview missing/cancelled uuid=%s", p.interviewUuid());
                return HandlerResult.ok();
            }
            String eventId = outlook.createEvent(new CreateEventCommand(
                    p.interviewUuid(),
                    p.organizerMailbox(),
                    p.subject(),
                    p.bodyHtml(),
                    p.startUtc(),
                    p.endUtc(),
                    p.attendeeEmails(),
                    p.teamsEnabled()
            ));
            iv.outlookEventId = eventId;
            return HandlerResult.ok();
        } catch (OutlookCalendarException ex) {
            return ex.isRetryable()
                    ? HandlerResult.retryable(ex.getErrorCode() + ": " + ex.getDetail())
                    : HandlerResult.terminal(ex.getErrorCode() + ": " + ex.getDetail());
        } catch (Exception ex) {
            LOG.warnf(ex, "OutlookCreate: dispatch error row=%s", row.uuid);
            return HandlerResult.retryable("dispatch_error: " + ex.getMessage());
        }
    }

    Interview findInterview(String uuid) {
        return Interview.findById(uuid);
    }
}
