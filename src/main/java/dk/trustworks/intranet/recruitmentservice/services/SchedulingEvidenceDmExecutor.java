package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.slack.SlackAvailabilityViews;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SEND_EVIDENCE_DM (plan §12.4): the outbox-carried messages of the
 * availability loop — the D6 summary card (SUMMARY: Bekræft/Ret for a
 * PENDING row, Ret-only for an auto-confirmed one) and the finalization
 * stale-evidence notice (RECONFIRM, spec §23). Outbox rather than a
 * fire-and-forget send because a lost summary card would strand its
 * evidence in PENDING forever — retries matter here.
 * <p>
 * Replay-safe: evidence whose status moved past the card's purpose by
 * execution time (CANCELLED/SUPERSEDED/EXPIRED/REJECTED) is skipped
 * silently; a duplicate card after a crash between send and completion
 * bookkeeping is the accepted worst case (both cards' buttons carry the
 * same evidence uuid and the handlers are idempotent).
 */
@JBossLog
@ApplicationScoped
public class SchedulingEvidenceDmExecutor implements SchedulingOutboxExecutor {

    @Inject
    SlackService slackService;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public SchedulingOutboxAction action() {
        return SchedulingOutboxAction.SEND_EVIDENCE_DM;
    }

    @Override
    public void execute(RecruitmentSchedulingOutbox row) throws Exception {
        Payload payload = QuarkusTransaction.requiringNew().call(() -> load(row));
        if (payload == null) {
            return; // stale action — nothing to send
        }
        if (payload.reconfirmText != null) {
            slackService.sendMessage(payload.interviewer,
                    "Interview booket — bemærk", java.util.List.of(
                            com.slack.api.model.block.Blocks.section(s -> s.text(
                                    com.slack.api.model.block.composition.BlockCompositions
                                            .markdownText(payload.reconfirmText)))));
            return;
        }
        slackService.sendMessage(payload.interviewer,
                SlackAvailabilityViews.summaryFallback(payload.evidence),
                SlackAvailabilityViews.summaryCard(payload.evidence, payload.constraints,
                        payload.pendingConfirmation));
    }

    private record Payload(User interviewer, RecruitmentAvailabilityEvidence evidence,
                           List<RecruitmentAvailabilityConstraint> constraints,
                           boolean pendingConfirmation, String reconfirmText) {
    }

    /** Null = the action is stale and counts as done. */
    private Payload load(RecruitmentSchedulingOutbox row) throws Exception {
        JsonNode payload = objectMapper.readTree(
                row.getPayloadJson() == null ? "{}" : row.getPayloadJson());
        String evidenceUuid = payload.path("evidenceUuid").asText(null);
        RecruitmentAvailabilityEvidence evidence = evidenceUuid == null ? null
                : RecruitmentAvailabilityEvidence.findById(evidenceUuid);
        if (evidence == null) {
            return null;
        }
        User interviewer = User.findById(evidence.getUserUuid());
        if (interviewer == null || interviewer.getSlackusername() == null
                || interviewer.getSlackusername().isBlank()) {
            // Retrying cannot conjure a Slack link; fail loud so the row
            // dead-letters and surfaces as a cleanup warning.
            throw new IllegalStateException(
                    "Interviewer has no Slack link for evidence " + evidenceUuid);
        }
        if ("RECONFIRM".equals(payload.path("kind").asText(""))) {
            LocalDateTime slotStart = LocalDateTime.parse(payload.path("slotStart").asText());
            LocalDateTime slotEnd = LocalDateTime.parse(payload.path("slotEnd").asText());
            return new Payload(interviewer, evidence, List.of(), false,
                    SlackAvailabilityViews.reconfirmNoticeText(
                            evidence.getLanguage(), slotStart, slotEnd));
        }
        EvidenceConfirmationStatus status = evidence.getConfirmationStatus();
        if (status != EvidenceConfirmationStatus.PENDING
                && status != EvidenceConfirmationStatus.CONFIRMED) {
            return null; // answered/withdrawn before the card went out
        }
        List<RecruitmentAvailabilityConstraint> constraints =
                RecruitmentAvailabilityConstraint.list("evidenceUuid = ?1", evidence.getUuid());
        return new Payload(interviewer, evidence, constraints,
                status == EvidenceConfirmationStatus.PENDING, null);
    }
}
