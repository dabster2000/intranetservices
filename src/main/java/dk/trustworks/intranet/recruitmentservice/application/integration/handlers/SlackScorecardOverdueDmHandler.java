package dk.trustworks.intranet.recruitmentservice.application.integration.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.SlackDmPayload;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import dk.trustworks.intranet.recruitmentservice.infrastructure.SlackException;
import dk.trustworks.intranet.recruitmentservice.ports.SlackPort;
import dk.trustworks.intranet.recruitmentservice.ports.slack.SendDmCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Dispatches {@code SLACK_SCORECARD_OVERDUE_DM} rows via {@link SlackPort}.
 * Identical implementation to {@link SlackInterviewTomorrowDmHandler}; kept as
 * a separate class so the dispatcher can route by kind without inspecting
 * payload contents.
 */
@ApplicationScoped
public class SlackScorecardOverdueDmHandler implements OutboxHandler {

    private static final Logger LOG = Logger.getLogger(SlackScorecardOverdueDmHandler.class);

    @Inject SlackPort slack;
    @Inject ObjectMapper mapper;

    @Override
    @Transactional
    public HandlerResult handle(RecruitmentOutboxRow row) {
        try {
            SlackDmPayload p = mapper.readValue(row.payloadJson, SlackDmPayload.class);
            slack.sendDirectMessage(new SendDmCommand(
                    p.recipientUserUuid(),
                    p.headline(),
                    p.bodyMarkdown(),
                    p.deepLinkUrl(),
                    p.idempotencyKey()
            ));
            return HandlerResult.ok();
        } catch (SlackException ex) {
            return ex.isRetryable()
                    ? HandlerResult.retryable(ex.getErrorCode() + ": " + ex.getDetail())
                    : HandlerResult.terminal(ex.getErrorCode() + ": " + ex.getDetail());
        } catch (Exception ex) {
            LOG.warnf(ex, "SlackScorecardOverdueDm: dispatch error row=%s", row.uuid);
            return HandlerResult.retryable("dispatch_error: " + ex.getMessage());
        }
    }
}
