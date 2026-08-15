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
    SlackProposalCardService cardService;

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
            refreshCards(resolved, request);
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

        refreshCards(resolved, request);
        return SlackInboundResponse.handled(null);
    }

    /** Re-render every proposal message of the request from state —
     * including the exact message that was clicked, when it is an older
     * card whose stored ref a reminder overwrote. */
    private void refreshCards(SlackSchedulingSupport.Resolved resolved,
                              SlackInboundRequest request) {
        cardService.perform(cardService.computeRefreshAfterAction(
                resolved.request(), resolved.approval(),
                request.channelId(), request.messageTs()));
    }
}
