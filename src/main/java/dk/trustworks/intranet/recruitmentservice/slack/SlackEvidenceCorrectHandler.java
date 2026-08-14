package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * The summary card's <b>Ret</b> button (plan §12.4): the interviewer
 * says the reading is wrong. The evidence is CANCELLED on the spot —
 * deliberately not left PENDING for a later supersede: D9's whole point
 * is that a disputed reading must never sit around confirmable — and
 * the card asks for a fresh message, which runs a NEW extraction round.
 * A confirmed row can be corrected the same way: pressing Ret on its
 * card withdraws it from the engine immediately.
 */
@JBossLog
@ApplicationScoped
public class SlackEvidenceCorrectHandler implements SlackInboundHandler {

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    SlackService slackService;

    @Override
    public String key() {
        return SlackAvailabilityViews.ACTION_EVIDENCE_CORRECT;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!methodBFlag.isMethodBEnabled()) {
            return SlackInboundResponse.disabled(SlackInboundDispatchService.DISABLED_TEXT);
        }
        RecruitmentAvailabilityEvidence evidence =
                SlackEvidenceConfirmHandler.resolve(actor, request.actionValue());
        if (evidence == null) {
            return SlackInboundResponse.handled(SlackEvidenceConfirmHandler.GONE_TEXT);
        }
        boolean wasConfirmed =
                evidence.getConfirmationStatus() == EvidenceConfirmationStatus.CONFIRMED;
        if (evidence.getConfirmationStatus() != EvidenceConfirmationStatus.PENDING
                && !wasConfirmed) {
            return SlackInboundResponse.handled(SlackEvidenceConfirmHandler.GONE_TEXT);
        }

        evidence.setConfirmationStatus(EvidenceConfirmationStatus.CANCELLED);
        RecruitmentSchedulingRequest schedulingRequest =
                RecruitmentSchedulingRequest.findById(evidence.getRequestUuid());
        if (schedulingRequest != null && !schedulingRequest.getStatus().isTerminal()
                && wasConfirmed) {
            // Withdrawn scheduling input — replan without it.
            schedulingRequest.setNextActionAt(null);
        }
        record(schedulingRequest, evidence, actor.getUuid());

        updateCard(evidence, request);
        return SlackInboundResponse.handled(null);
    }

    private void record(RecruitmentSchedulingRequest schedulingRequest,
                        RecruitmentAvailabilityEvidence evidence, String actorUuid) {
        RecruitmentApplication application = schedulingRequest == null ? null
                : RecruitmentApplication.findById(schedulingRequest.getApplicationUuid());
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.AVAILABILITY_EVIDENCE_CANCELLED)
                .application(schedulingRequest != null
                        ? schedulingRequest.getApplicationUuid() : null)
                .candidate(application != null ? application.getCandidateUuid() : null)
                .position(application != null ? application.getPositionUuid() : null)
                .actorUser(actorUuid)
                .payload("request_uuid", evidence.getRequestUuid())
                .payload("evidence_uuid", evidence.getUuid())
                .payload("reason", "CORRECTION_REQUESTED"));
    }

    private void updateCard(RecruitmentAvailabilityEvidence evidence,
                            SlackInboundRequest request) {
        String channel = request.channelId() != null
                ? request.channelId() : evidence.getSlackChannelId();
        String ts = request.messageTs();
        if (channel == null || ts == null) {
            return;
        }
        slackService.updateMessage(channel, ts,
                "Tilgængelighed kasseret",
                SlackAvailabilityViews.cancelledCard(evidence));
    }
}
