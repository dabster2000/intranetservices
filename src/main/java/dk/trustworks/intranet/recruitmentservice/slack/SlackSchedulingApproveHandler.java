package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.model.enums.SlotApprovalStatus;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The proposal card's <b>Godkend</b> button (plan §9.2). Persists the
 * approval and rewrites the card in place (✓, buttons gone) inside the
 * dispatch transaction — well within Slack's 3 s ack. The heavy state
 * advance (recheck + holds) deliberately rides the 1-minute advance
 * sweep instead of an in-request executor: the sweep re-derives from
 * persisted state, so a deploy between ack and advance costs nothing.
 */
@JBossLog
@ApplicationScoped
public class SlackSchedulingApproveHandler implements SlackInboundHandler {

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    SlackSchedulingSupport support;

    @Inject
    SlackService slackService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Override
    public String key() {
        return SlackSchedulingViews.ACTION_APPROVE;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!methodBFlag.isMethodBEnabled()) {
            return SlackInboundResponse.disabled(SlackInboundDispatchService.DISABLED_TEXT);
        }
        SlackSchedulingSupport.Resolved resolved =
                support.resolve(actor, request.actionValue());
        if (resolved.deny() != null) {
            return resolved.deny();
        }
        RecruitmentSlotApproval approval = resolved.approval();
        if (!resolved.slot().getStatus().isLive()) {
            updateCardClosed(resolved, request);
            return SlackInboundResponse.handled(SlackSchedulingSupport.GONE_TEXT);
        }
        if (approval.getStatus() != SlotApprovalStatus.PENDING) {
            return SlackInboundResponse.handled("Du har allerede besvaret dette forslag.");
        }

        approval.setStatus(SlotApprovalStatus.APPROVED);
        approval.setRespondedAt(LocalDateTime.now());
        List<RecruitmentSlotApproval> approvals =
                RecruitmentSlotApproval.list("slotUuid = ?1", approval.getSlotUuid());
        resolved.slot().setStatus(
                RecruitmentSchedulingService.recomputeSlotStatus(approvals));
        // Wake the advance sweep — an APPROVED slot goes to recheck+holds.
        resolved.request().setNextActionAt(null);

        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.SLOT_APPROVED)
                .application(resolved.request().getApplicationUuid())
                .candidate(resolved.application() != null
                        ? resolved.application().getCandidateUuid() : null)
                .position(resolved.application() != null
                        ? resolved.application().getPositionUuid() : null)
                .actorUser(actor.getUuid())
                .payload("request_uuid", resolved.request().getUuid())
                .payload("slot_uuid", resolved.slot().getUuid())
                .payload("origin", "slack"));

        updateCard(resolved, request, true);
        return SlackInboundResponse.handled(null);
    }

    private void updateCard(SlackSchedulingSupport.Resolved resolved,
                            SlackInboundRequest request, boolean approved) {
        String channel = channelOf(resolved.approval(), request);
        String ts = tsOf(resolved.approval(), request);
        if (channel == null || ts == null) {
            return;
        }
        slackService.updateMessage(channel, ts,
                "Interviewforslag — besvaret",
                SlackSchedulingViews.answeredCard(resolved.request(), resolved.slot(),
                        resolved.candidateName(), resolved.positionTitle(), approved));
    }

    private void updateCardClosed(SlackSchedulingSupport.Resolved resolved,
                                  SlackInboundRequest request) {
        String channel = channelOf(resolved.approval(), request);
        String ts = tsOf(resolved.approval(), request);
        if (channel == null || ts == null) {
            return;
        }
        slackService.updateMessage(channel, ts,
                "Interviewforslag — lukket",
                SlackSchedulingViews.closedCard(resolved.request(), resolved.slot(),
                        resolved.candidateName(), resolved.positionTitle()));
    }

    /** The stored DM ref wins; the interaction's own channel/ts is the
     * fallback (a nudge card, or a ref the executor failed to store). */
    static String channelOf(RecruitmentSlotApproval approval, SlackInboundRequest request) {
        return approval.getSlackChannelId() != null
                ? approval.getSlackChannelId() : request.channelId();
    }

    static String tsOf(RecruitmentSlotApproval approval, SlackInboundRequest request) {
        // The clicked message is the one to rewrite when the click came
        // from a different (older/nudge) card than the stored ref.
        return request.messageTs() != null
                ? request.messageTs() : approval.getSlackMessageTs();
    }
}
