package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.model.enums.SlotApprovalStatus;
import dk.trustworks.intranet.recruitmentservice.slack.SlackProposalCardService;
import dk.trustworks.intranet.recruitmentservice.slack.SlackSchedulingViews;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SEND_PROPOSAL_DM (plan §9.1): posts ONE combined proposal message per
 * interviewer per round — every option of the round with its own
 * Godkend/Afvis pair — and stores the resolved channel/ts on ALL the
 * round's approval rows: the {@code chat.update} address every later
 * state change re-renders through {@link SlackProposalCardService}.
 * {@code nudge} &gt; 0 posts the reminder variant (a fresh combined
 * card; buttons on every delivered card stay valid — they all carry
 * approval uuids).
 * <p>
 * Payload: {@code {"approvalUuids": [...]}}; the pre-combined
 * single-approval shape ({@code {"approvalUuid": ...}}) is still
 * accepted so actions enqueued before the change (and the
 * replace-interviewer path) replay as one-option messages.
 * <p>
 * Replay-safe: a round whose every approval is answered — or whose
 * slots all died — by execution time is skipped silently; a duplicate
 * DM after a crash between send and bookkeeping is the accepted worst
 * case.
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
                SlackSchedulingViews.combinedFallback(
                        payload.options.size(), payload.nudge, false),
                SlackSchedulingViews.combinedProposalCard(payload.request,
                        payload.options, payload.candidateName,
                        payload.positionTitle, payload.nudge));
        QuarkusTransaction.requiringNew().run(() -> {
            for (SlackSchedulingViews.OptionView option : payload.options) {
                RecruitmentSlotApproval approval =
                        RecruitmentSlotApproval.findById(option.approvalUuid());
                if (approval != null) {
                    approval.setSlackChannelId(ref.channelId());
                    approval.setSlackMessageTs(ref.ts());
                }
            }
        });
    }

    private record Payload(int nudge, User interviewer,
                           RecruitmentSchedulingRequest request,
                           List<SlackSchedulingViews.OptionView> options,
                           String candidateName, String positionTitle) {
    }

    /** Null = the action is stale and counts as done. */
    private Payload load(RecruitmentSchedulingOutbox row) throws Exception {
        JsonNode payload = objectMapper.readTree(
                row.getPayloadJson() == null ? "{}" : row.getPayloadJson());
        List<String> approvalUuids = new ArrayList<>();
        payload.path("approvalUuids").forEach(node -> approvalUuids.add(node.asText()));
        String single = payload.path("approvalUuid").asText(null);
        if (approvalUuids.isEmpty() && single != null) {
            approvalUuids.add(single);
        }
        int nudge = payload.path("nudge").asInt(0);

        record Row(RecruitmentSlotApproval approval, RecruitmentProposedSlot slot) {
        }
        List<Row> rows = new ArrayList<>();
        RecruitmentSchedulingRequest request = null;
        boolean anyPending = false;
        for (String approvalUuid : approvalUuids) {
            RecruitmentSlotApproval approval =
                    RecruitmentSlotApproval.findById(approvalUuid);
            if (approval == null) {
                continue;
            }
            RecruitmentProposedSlot slot =
                    RecruitmentProposedSlot.findById(approval.getSlotUuid());
            if (slot == null) {
                continue;
            }
            if (request == null) {
                request = RecruitmentSchedulingRequest.findById(slot.getRequestUuid());
            }
            rows.add(new Row(approval, slot));
            anyPending |= approval.getStatus() == SlotApprovalStatus.PENDING
                    && (slot.getStatus() == ProposedSlotStatus.PROPOSED
                            || slot.getStatus() == ProposedSlotStatus.PARTIALLY_APPROVED);
        }
        if (rows.isEmpty() || request == null || request.getStatus().isTerminal()
                || !anyPending) {
            return null;
        }
        User interviewer = User.findById(rows.getFirst().approval().getUserUuid());
        if (interviewer == null || interviewer.getSlackusername() == null
                || interviewer.getSlackusername().isBlank()) {
            // Retrying cannot conjure a Slack link; fail loud so the row
            // dead-letters and surfaces as a cleanup warning.
            throw new IllegalStateException(
                    "Interviewer has no Slack link for outbox row " + row.getUuid());
        }
        List<SlackSchedulingViews.OptionView> options = rows.stream()
                .sorted(Comparator.comparingInt(r -> r.slot().getOptionNo()))
                .map(r -> new SlackSchedulingViews.OptionView(r.slot(),
                        r.approval().getUuid(),
                        SlackSchedulingViews.optionState(r.slot().getStatus(),
                                r.approval().getStatus(), r.slot().getRejectReason())))
                .toList();
        SlackProposalCardService.Names names = SlackProposalCardService.namesOf(request);
        return new Payload(nudge, interviewer, request, options,
                names.candidateName(), names.positionTitle());
    }
}
