package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.model.enums.SlotApprovalStatus;
import dk.trustworks.intranet.recruitmentservice.slack.SlackSchedulingViews;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * SEND_PROPOSAL_DM (plan §9.1): posts one proposal card as a DM to one
 * required interviewer and stores the resolved channel/ts on the
 * approval row — the {@code chat.update} address the button handlers
 * rewrite in place. {@code nudge} &gt; 0 in the payload posts the
 * reminder variant (a fresh card; every card's buttons stay valid,
 * they all carry the same approval uuid).
 * <p>
 * Replay-safe: an approval that is answered — or whose slot died —
 * by execution time is skipped silently; a duplicate DM after a crash
 * between send and bookkeeping is the accepted worst case.
 */
@JBossLog
@ApplicationScoped
public class SchedulingProposalDmExecutor implements SchedulingOutboxExecutor {

    @Inject
    SlackService slackService;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public SchedulingOutboxAction action() {
        return SchedulingOutboxAction.SEND_PROPOSAL_DM;
    }

    @Override
    public void execute(RecruitmentSchedulingOutbox row) throws Exception {
        Payload payload = QuarkusTransaction.requiringNew().call(() -> load(row));
        if (payload == null) {
            return; // stale action — nothing to send
        }
        SlackService.DmRef ref = slackService.sendDmReturningRef(
                payload.interviewer,
                SlackSchedulingViews.proposalFallback(payload.slot, payload.nudge),
                SlackSchedulingViews.proposalCard(payload.request, payload.slot,
                        payload.candidateName, payload.positionTitle,
                        payload.approvalUuid, payload.nudge));
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentSlotApproval approval =
                    RecruitmentSlotApproval.findById(payload.approvalUuid);
            if (approval != null) {
                approval.setSlackChannelId(ref.channelId());
                approval.setSlackMessageTs(ref.ts());
            }
        });
    }

    private record Payload(String approvalUuid, int nudge, User interviewer,
                           RecruitmentSchedulingRequest request,
                           RecruitmentProposedSlot slot,
                           String candidateName, String positionTitle) {
    }

    /** Null = the action is stale and counts as done. */
    private Payload load(RecruitmentSchedulingOutbox row) throws Exception {
        JsonNode payload = objectMapper.readTree(
                row.getPayloadJson() == null ? "{}" : row.getPayloadJson());
        String approvalUuid = payload.path("approvalUuid").asText(null);
        int nudge = payload.path("nudge").asInt(0);
        RecruitmentSlotApproval approval = approvalUuid == null ? null
                : RecruitmentSlotApproval.findById(approvalUuid);
        if (approval == null || approval.getStatus() != SlotApprovalStatus.PENDING) {
            return null;
        }
        RecruitmentProposedSlot slot = RecruitmentProposedSlot.findById(approval.getSlotUuid());
        if (slot == null || !(slot.getStatus() == ProposedSlotStatus.PROPOSED
                || slot.getStatus() == ProposedSlotStatus.PARTIALLY_APPROVED)) {
            return null;
        }
        RecruitmentSchedulingRequest request =
                RecruitmentSchedulingRequest.findById(slot.getRequestUuid());
        if (request == null || request.getStatus().isTerminal()) {
            return null;
        }
        User interviewer = User.findById(approval.getUserUuid());
        if (interviewer == null || interviewer.getSlackusername() == null
                || interviewer.getSlackusername().isBlank()) {
            // Retrying cannot conjure a Slack link; fail loud so the row
            // dead-letters and surfaces as a cleanup warning.
            throw new IllegalStateException(
                    "Interviewer has no Slack link for approval " + approvalUuid);
        }
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        RecruitmentCandidate candidate = application == null ? null
                : RecruitmentCandidate.findById(application.getCandidateUuid());
        RecruitmentPosition position = application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
        String candidateName = candidate == null ? "kandidaten"
                : ((candidate.getFirstName() == null ? "" : candidate.getFirstName())
                        + " " + (candidate.getLastName() == null ? "" : candidate.getLastName())).trim();
        return new Payload(approvalUuid, nudge, interviewer, request, slot,
                candidateName.isEmpty() ? "kandidaten" : candidateName,
                position != null ? position.getTitle() : null);
    }
}
