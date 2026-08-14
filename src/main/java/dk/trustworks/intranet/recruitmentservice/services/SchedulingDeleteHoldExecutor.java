package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCalendarHold;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.enums.CalendarHoldStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;

/**
 * DELETE_HOLD (plan §9.4): silently removes one hold's Graph event —
 * 404 counts as done (the owner beat us to it). Retries via the outbox
 * until Graph answers; a row that exhausts its attempts dead-letters
 * and keeps a cleanup warning on the request (spec §21.5).
 */
@JBossLog
@ApplicationScoped
public class SchedulingDeleteHoldExecutor implements SchedulingOutboxExecutor {

    @Inject
    RecruitmentCalendarService calendarService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public SchedulingOutboxAction action() {
        return SchedulingOutboxAction.DELETE_HOLD;
    }

    @Override
    public void execute(RecruitmentSchedulingOutbox row) throws Exception {
        String holdUuid = objectMapper.readTree(
                        row.getPayloadJson() == null ? "{}" : row.getPayloadJson())
                .path("holdUuid").asText(null);
        Target target = QuarkusTransaction.requiringNew().call(() -> load(holdUuid));
        if (target == null) {
            return; // already released or never reached Graph
        }
        calendarService.deleteHoldEvent(target.mailbox, target.graphEventId);
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentCalendarHold hold = RecruitmentCalendarHold.findById(holdUuid);
            if (hold == null || hold.getStatus() == CalendarHoldStatus.RELEASED) {
                return;
            }
            hold.setStatus(CalendarHoldStatus.RELEASED);
            hold.setReleasedAt(LocalDateTime.now());
            RecruitmentProposedSlot slot = RecruitmentProposedSlot.findById(hold.getSlotUuid());
            RecruitmentSchedulingRequest request = slot == null ? null
                    : RecruitmentSchedulingRequest.findById(slot.getRequestUuid());
            RecruitmentApplication application = request == null ? null
                    : RecruitmentApplication.findById(request.getApplicationUuid());
            eventRecorder.record(RecruitmentEventBuilder
                    .event(RecruitmentEventType.HOLD_RELEASED)
                    .application(request != null ? request.getApplicationUuid() : null)
                    .candidate(application != null ? application.getCandidateUuid() : null)
                    .position(application != null ? application.getPositionUuid() : null)
                    .actorScheduler()
                    .payload("request_uuid", slot != null ? slot.getRequestUuid() : null)
                    .payload("slot_uuid", hold.getSlotUuid())
                    .payload("hold_uuid", hold.getUuid())
                    .payload("owner_kind", hold.getOwnerKind().name()));
        });
    }

    private record Target(String mailbox, String graphEventId) {
    }

    /** Null = nothing to delete out there; mark-released is handled by
     * the enqueue path for Graph-less holds. */
    private Target load(String holdUuid) {
        RecruitmentCalendarHold hold = holdUuid == null ? null
                : RecruitmentCalendarHold.findById(holdUuid);
        if (hold == null || hold.getStatus() == CalendarHoldStatus.RELEASED
                || hold.getGraphEventId() == null) {
            return null;
        }
        return new Target(hold.getMailbox(), hold.getGraphEventId());
    }
}
