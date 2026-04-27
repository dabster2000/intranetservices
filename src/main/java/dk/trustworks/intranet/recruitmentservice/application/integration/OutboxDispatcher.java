package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.HandlerResult;
import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.OutboxHandler;
import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.OutlookCancelHandler;
import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.OutlookCreateHandler;
import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.OutlookUpdateHandler;
import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.SlackInterviewTomorrowDmHandler;
import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.SlackScorecardOverdueDmHandler;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.EnumMap;
import java.util.Map;

/**
 * Routes a {@link RecruitmentOutboxRow} to the {@link OutboxHandler} matching
 * its {@link OutboxKind}. Constructed once at boot via {@link PostConstruct}.
 */
@ApplicationScoped
public class OutboxDispatcher {

    @Inject OutlookCreateHandler outlookCreate;
    @Inject OutlookUpdateHandler outlookUpdate;
    @Inject OutlookCancelHandler outlookCancel;
    @Inject SlackInterviewTomorrowDmHandler slackTomorrow;
    @Inject SlackScorecardOverdueDmHandler slackOverdue;

    private final Map<OutboxKind, OutboxHandler> handlers = new EnumMap<>(OutboxKind.class);

    @PostConstruct
    void init() {
        register(OutboxKind.OUTLOOK_EVENT_CREATE, outlookCreate);
        register(OutboxKind.OUTLOOK_EVENT_UPDATE, outlookUpdate);
        register(OutboxKind.OUTLOOK_EVENT_CANCEL, outlookCancel);
        register(OutboxKind.SLACK_INTERVIEW_TOMORROW_DM, slackTomorrow);
        register(OutboxKind.SLACK_SCORECARD_OVERDUE_DM, slackOverdue);
    }

    /** Test-friendly seam: register a handler for a kind. */
    public void register(OutboxKind kind, OutboxHandler handler) {
        handlers.put(kind, handler);
    }

    public HandlerResult dispatch(RecruitmentOutboxRow row) {
        OutboxHandler handler = handlers.get(row.kind);
        if (handler == null) {
            return HandlerResult.terminal("no_handler_for_kind: " + row.kind);
        }
        return handler.handle(row);
    }
}
