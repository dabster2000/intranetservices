package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.HandlerOutcome;
import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.HandlerResult;
import dk.trustworks.intranet.recruitmentservice.application.integration.handlers.OutboxHandler;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDispatcherTest {

    private static RecruitmentOutboxRow rowFor(OutboxKind kind) {
        return RecruitmentOutboxRow.create(kind, "k-" + kind, null, "{}");
    }

    @Test
    void dispatch_routes_outlook_create_to_create_handler() {
        OutboxHandler create = mock(OutboxHandler.class);
        OutboxHandler update = mock(OutboxHandler.class);
        HandlerResult ok = HandlerResult.ok();
        when(create.handle(any())).thenReturn(ok);

        OutboxDispatcher dispatcher = new OutboxDispatcher();
        dispatcher.register(OutboxKind.OUTLOOK_EVENT_CREATE, create);
        dispatcher.register(OutboxKind.OUTLOOK_EVENT_UPDATE, update);

        RecruitmentOutboxRow row = rowFor(OutboxKind.OUTLOOK_EVENT_CREATE);
        HandlerResult result = dispatcher.dispatch(row);

        assertSame(ok, result);
        verify(create).handle(row);
        verify(update, never()).handle(any());
    }

    @Test
    void dispatch_routes_slack_tomorrow_to_tomorrow_handler() {
        OutboxHandler tomorrow = mock(OutboxHandler.class);
        OutboxHandler overdue = mock(OutboxHandler.class);
        when(tomorrow.handle(any())).thenReturn(HandlerResult.ok());

        OutboxDispatcher dispatcher = new OutboxDispatcher();
        dispatcher.register(OutboxKind.SLACK_INTERVIEW_TOMORROW_DM, tomorrow);
        dispatcher.register(OutboxKind.SLACK_SCORECARD_OVERDUE_DM, overdue);

        RecruitmentOutboxRow row = rowFor(OutboxKind.SLACK_INTERVIEW_TOMORROW_DM);
        dispatcher.dispatch(row);

        verify(tomorrow).handle(row);
        verify(overdue, never()).handle(any());
    }

    @Test
    void dispatch_returns_terminal_when_no_handler_registered() {
        OutboxDispatcher dispatcher = new OutboxDispatcher();
        // No handlers registered for OUTLOOK_EVENT_CANCEL.
        HandlerResult result = dispatcher.dispatch(rowFor(OutboxKind.OUTLOOK_EVENT_CANCEL));
        assertEquals(HandlerOutcome.TERMINAL, result.outcome());
    }

    private static RecruitmentOutboxRow any() {
        return org.mockito.ArgumentMatchers.any(RecruitmentOutboxRow.class);
    }
}
