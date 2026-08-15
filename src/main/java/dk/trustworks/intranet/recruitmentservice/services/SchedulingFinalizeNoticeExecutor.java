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

    @Inject
    dk.trustworks.intranet.recruitmentservice.slack.SlackProposalCardService cardService;

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
        // One state-render pass over every proposal message: the winning
        // option shows booked, everything else closed. A dead message
        // (deactivated user, deleted DM) never blocks the notices —
        // cards are cosmetic, the DM below is the message that matters.
        cardService.perform(payload.cardUpdates);
        for (Map.Entry<User, String> notice : payload.notices.entrySet()) {
            slackService.sendMessage(notice.getKey(), notice.getValue(),
                    List.of(com.slack.api.model.block.Blocks.section(s -> s.text(
                            com.slack.api.model.block.composition.BlockCompositions
                                    .markdownText(notice.getValue())))));
        }
    }

    private record Payload(
            List<dk.trustworks.intranet.recruitmentservice.slack
                    .SlackProposalCardService.ComputedUpdate> cardUpdates,
            Map<User, String> notices) {
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
        String candidateName =
                dk.trustworks.intranet.recruitmentservice.slack
                        .SlackProposalCardService.namesOf(request).candidateName();

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
        return new Payload(cardService.computeRequestRefresh(request), notices);
    }
}
