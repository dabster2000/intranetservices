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
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.slack.SlackSchedulingViews;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NOTIFY_FINALIZED (spec §16.3 — "notify recruiter and interviewers"):
 * after the candidate's choice became a real interview, close the Slack
 * loop with the interviewers, who until now would only learn of the
 * outcome from the Outlook invitation:
 * <ul>
 *   <li>the WINNING slot's proposal cards are rewritten to the booked
 *       state ({@code chat.update} on the stored refs);</li>
 *   <li>every other card of the request is rewritten to the closed
 *       state, so no stale "reserveret foreløbigt" survives;</li>
 *   <li>each required interviewer gets ONE booked-time DM line.</li>
 * </ul>
 * Replay-safe: card rewrites are idempotent; the worst case of a crash
 * between send and completion bookkeeping is one duplicated DM line.
 */
@JBossLog
@ApplicationScoped
public class SchedulingFinalizeNoticeExecutor implements SchedulingOutboxExecutor {

    @Inject
    SlackService slackService;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public SchedulingOutboxAction action() {
        return SchedulingOutboxAction.NOTIFY_FINALIZED;
    }

    @Override
    public void execute(RecruitmentSchedulingOutbox row) throws Exception {
        Payload payload = QuarkusTransaction.requiringNew().call(() -> load(row));
        if (payload == null) {
            return; // stale action — nothing to notify
        }
        for (CardUpdate card : payload.cardUpdates) {
            try {
                slackService.updateMessage(card.channelId, card.ts,
                        card.booked ? "Interview booket" : "Interviewforslag — lukket",
                        card.blocks);
            } catch (Exception e) {
                // One dead card (deactivated user, deleted DM) must not
                // hold the booked notices hostage — cards are cosmetic,
                // the DM below is the message that matters.
                log.warnf("Method B finalize card update failed (channel=%s): %s",
                        card.channelId, e.getMessage());
            }
        }
        for (Map.Entry<User, String> notice : payload.notices.entrySet()) {
            slackService.sendMessage(notice.getKey(), notice.getValue(),
                    List.of(com.slack.api.model.block.Blocks.section(s -> s.text(
                            com.slack.api.model.block.composition.BlockCompositions
                                    .markdownText(notice.getValue())))));
        }
    }

    private record CardUpdate(String channelId, String ts, boolean booked,
                              List<com.slack.api.model.block.LayoutBlock> blocks) {
    }

    private record Payload(List<CardUpdate> cardUpdates, Map<User, String> notices) {
    }

    /** Null = the action is stale and counts as done. */
    private Payload load(RecruitmentSchedulingOutbox row) throws Exception {
        JsonNode payload = objectMapper.readTree(
                row.getPayloadJson() == null ? "{}" : row.getPayloadJson());
        String selectedSlotUuid = payload.path("selectedSlotUuid").asText(null);
        RecruitmentProposedSlot selected = selectedSlotUuid == null ? null
                : RecruitmentProposedSlot.findById(selectedSlotUuid);
        RecruitmentSchedulingRequest request = selected == null ? null
                : RecruitmentSchedulingRequest.findById(selected.getRequestUuid());
        if (selected == null || request == null) {
            return null;
        }
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        RecruitmentCandidate candidate = application == null ? null
                : RecruitmentCandidate.findById(application.getCandidateUuid());
        RecruitmentPosition position = application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
        String candidateName = candidate == null ? "kandidaten"
                : ((candidate.getFirstName() == null ? "" : candidate.getFirstName())
                        + " " + (candidate.getLastName() == null ? "" : candidate.getLastName()))
                        .trim();
        if (candidateName.isEmpty()) {
            candidateName = "kandidaten";
        }
        String positionTitle = position != null ? position.getTitle() : null;

        List<CardUpdate> cards = new ArrayList<>();
        List<RecruitmentProposedSlot> slots = RecruitmentProposedSlot
                .list("requestUuid = ?1", request.getUuid());
        for (RecruitmentProposedSlot slot : slots) {
            boolean booked = slot.getUuid().equals(selected.getUuid());
            List<RecruitmentSlotApproval> approvals = RecruitmentSlotApproval
                    .list("slotUuid = ?1", slot.getUuid());
            for (RecruitmentSlotApproval approval : approvals) {
                if (approval.getSlackChannelId() == null
                        || approval.getSlackMessageTs() == null) {
                    continue;
                }
                cards.add(new CardUpdate(approval.getSlackChannelId(),
                        approval.getSlackMessageTs(), booked,
                        booked
                                ? SlackSchedulingViews.bookedCard(request, slot,
                                        candidateName, positionTitle)
                                : SlackSchedulingViews.closedCard(request, slot,
                                        candidateName, positionTitle)));
            }
        }

        Map<User, String> notices = new LinkedHashMap<>();
        for (String interviewerUuid : request.getInterviewerUuids()) {
            User interviewer = User.findById(interviewerUuid);
            if (interviewer == null || interviewer.getSlackusername() == null
                    || interviewer.getSlackusername().isBlank()) {
                continue; // no Slack link — the Outlook invitation still lands
            }
            notices.put(interviewer,
                    SlackSchedulingViews.bookedNotice(selected, candidateName));
        }
        return new Payload(cards, notices);
    }
}
