package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SlotApprovalStatus;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The proposal card's <b>Afvis</b> button (plan §9.2): a hard decline
 * from a required interviewer rejects the slot outright (spec B14→B15) —
 * their ✗ card, the colleagues' "closed" cards, released holds when any
 * already existed, and a woken advance sweep that proposes the next
 * candidate slot.
 */
@JBossLog
@ApplicationScoped
public class SlackSchedulingDeclineHandler implements SlackInboundHandler {

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    SlackSchedulingSupport support;

    @Inject
    SlackService slackService;

    @Inject
    RecruitmentSchedulingService schedulingService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Override
    public String key() {
        return SlackSchedulingViews.ACTION_DECLINE;
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
            return SlackInboundResponse.handled(SlackSchedulingSupport.GONE_TEXT);
        }
        if (approval.getStatus() != SlotApprovalStatus.PENDING) {
            return SlackInboundResponse.handled("Du har allerede besvaret dette forslag.");
        }

        approval.setStatus(SlotApprovalStatus.DECLINED);
        approval.setRespondedAt(LocalDateTime.now());
        resolved.slot().setStatus(ProposedSlotStatus.REJECTED);
        resolved.slot().setRejectReason("INTERVIEWER_DECLINED");
        // A late decline can hit a slot that already carries holds
        // (another seat approved first) — compensate them away.
        schedulingService.releaseHolds(resolved.request(), resolved.slot());
        resolved.request().setNextActionAt(null);

        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.SLOT_DECLINED)
                .application(resolved.request().getApplicationUuid())
                .candidate(resolved.application() != null
                        ? resolved.application().getCandidateUuid() : null)
                .position(resolved.application() != null
                        ? resolved.application().getPositionUuid() : null)
                .actorUser(actor.getUuid())
                .payload("request_uuid", resolved.request().getUuid())
                .payload("slot_uuid", resolved.slot().getUuid())
                .payload("origin", "slack"));

        // The decliner's own card: ✗. Colleagues' cards: closed.
        String channel = SlackSchedulingApproveHandler.channelOf(approval, request);
        String ts = SlackSchedulingApproveHandler.tsOf(approval, request);
        if (channel != null && ts != null) {
            slackService.updateMessage(channel, ts, "Interviewforslag — besvaret",
                    SlackSchedulingViews.answeredCard(resolved.request(), resolved.slot(),
                            resolved.candidateName(), resolved.positionTitle(), false));
        }
        List<RecruitmentSlotApproval> siblings =
                RecruitmentSlotApproval.list("slotUuid = ?1", approval.getSlotUuid());
        for (RecruitmentSlotApproval sibling : siblings) {
            if (sibling.getUuid().equals(approval.getUuid())
                    || sibling.getSlackChannelId() == null
                    || sibling.getSlackMessageTs() == null) {
                continue;
            }
            slackService.updateMessage(sibling.getSlackChannelId(),
                    sibling.getSlackMessageTs(), "Interviewforslag — lukket",
                    SlackSchedulingViews.closedCard(resolved.request(), resolved.slot(),
                            resolved.candidateName(), resolved.positionTitle()));
        }
        return SlackInboundResponse.handled(
                "Forslaget er afvist — systemet leder efter en anden tid.");
    }
}
