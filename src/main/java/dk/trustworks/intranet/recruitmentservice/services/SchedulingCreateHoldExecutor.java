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
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CREATE_HOLD (plan §9.3, D5/D12): one attendee-less {@code [HOLD]}
 * event by direct write. The hold uuid is the Graph
 * {@code transactionId}, so the crash-replay window between the POST
 * and the bookkeeping commit cannot double-book. The subject carries
 * the option label, NEVER the candidate name (D12 — calendars are
 * shared surfaces); the body links the intranet application page for
 * the internal audience.
 */
@JBossLog
@ApplicationScoped
public class SchedulingCreateHoldExecutor implements SchedulingOutboxExecutor {

    @Inject
    RecruitmentCalendarService calendarService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    public SchedulingOutboxAction action() {
        return SchedulingOutboxAction.CREATE_HOLD;
    }

    @Override
    public void execute(RecruitmentSchedulingOutbox row) throws Exception {
        Work work = QuarkusTransaction.requiringNew().call(() -> load(row));
        if (work == null) {
            return; // stale — the hold is gone, released, or already created
        }
        String eventId = calendarService.createHoldEvent(
                work.mailbox, work.subject, work.body,
                work.slot.getSlotStart(), work.slot.getSlotEnd(),
                work.holdUuid);
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentCalendarHold hold = RecruitmentCalendarHold.findById(work.holdUuid);
            if (hold == null) {
                return;
            }
            hold.setGraphEventId(eventId);
            eventRecorder.record(RecruitmentEventBuilder
                    .event(RecruitmentEventType.HOLD_CREATED)
                    .application(work.applicationUuid)
                    .candidate(work.candidateUuid)
                    .position(work.positionUuid)
                    .actorScheduler()
                    .payload("request_uuid", work.slot.getRequestUuid())
                    .payload("slot_uuid", work.slot.getUuid())
                    .payload("hold_uuid", hold.getUuid())
                    .payload("owner_kind", hold.getOwnerKind().name()));
        });
    }

    private record Work(String holdUuid, String mailbox, String subject, String body,
                        RecruitmentProposedSlot slot, String applicationUuid,
                        String candidateUuid, String positionUuid) {
    }

    /** Null = stale action, counts as done. */
    private Work load(RecruitmentSchedulingOutbox row) throws Exception {
        String holdUuid = objectMapper.readTree(
                        row.getPayloadJson() == null ? "{}" : row.getPayloadJson())
                .path("holdUuid").asText(null);
        RecruitmentCalendarHold hold = holdUuid == null ? null
                : RecruitmentCalendarHold.findById(holdUuid);
        if (hold == null || hold.getStatus() == CalendarHoldStatus.RELEASED
                || hold.getGraphEventId() != null) {
            return null;
        }
        RecruitmentProposedSlot slot = RecruitmentProposedSlot.findById(hold.getSlotUuid());
        if (slot == null || !slot.getStatus().isLive()) {
            return null;
        }
        RecruitmentSchedulingRequest request =
                RecruitmentSchedulingRequest.findById(slot.getRequestUuid());
        if (request == null || request.getStatus().isTerminal()) {
            return null;
        }
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        String subject = "[HOLD] Interview — mulighed " + slot.getOptionNo()
                + "/" + request.getRequestedOptions();
        String body = "Foreløbig reservation til et interview via Trustworks intranettet.\n"
                + "Bekræftes eller frigives automatisk — slet den ikke selv.\n\n"
                + baseUrl + "/recruitment/applications/" + request.getApplicationUuid();
        return new Work(holdUuid, hold.getMailbox(), subject, body, slot,
                request.getApplicationUuid(),
                application != null ? application.getCandidateUuid() : null,
                application != null ? application.getPositionUuid() : null);
    }
}
