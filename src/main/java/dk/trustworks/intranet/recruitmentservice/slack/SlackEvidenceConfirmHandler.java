package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityMessageService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The summary card's <b>Bekræft</b> button (plan §12.4, D9): PENDING
 * evidence becomes CONFIRMED scheduling input, older overlapping
 * evidence from the same interviewer is superseded (spec §12.3), the
 * planner is woken, and the card is rewritten in place — all inside the
 * dispatch transaction, well within Slack's 3 s ack (the approve-handler
 * shape; no model involvement anywhere).
 */
@JBossLog
@ApplicationScoped
public class SlackEvidenceConfirmHandler implements SlackInboundHandler {

    static final String GONE_TEXT =
            "Denne tilgængelighedsoversigt er ikke længere aktiv.";

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    AvailabilityMessageService messageService;

    @Inject
    SlackService slackService;

    @Override
    public String key() {
        return SlackAvailabilityViews.ACTION_EVIDENCE_CONFIRM;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!methodBFlag.isMethodBEnabled()) {
            return SlackInboundResponse.disabled(SlackInboundDispatchService.DISABLED_TEXT);
        }
        RecruitmentAvailabilityEvidence evidence =
                resolve(actor, request.actionValue());
        if (evidence == null) {
            return SlackInboundResponse.handled(GONE_TEXT);
        }
        if (evidence.getConfirmationStatus() != EvidenceConfirmationStatus.PENDING) {
            return SlackInboundResponse.handled(
                    "Du har allerede besvaret denne oversigt.");
        }
        RecruitmentSchedulingRequest schedulingRequest =
                RecruitmentSchedulingRequest.findById(evidence.getRequestUuid());
        if (schedulingRequest == null || schedulingRequest.getStatus().isTerminal()) {
            return SlackInboundResponse.handled(GONE_TEXT);
        }

        evidence.setConfirmationStatus(EvidenceConfirmationStatus.CONFIRMED);
        evidence.setConfirmedAt(LocalDateTime.now());
        List<String> superseded = AvailabilityMessageService.supersedeOlder(evidence);
        schedulingRequest.setNextActionAt(null); // wake the planner
        messageService.recordConfirmed(schedulingRequest, evidence,
                actor.getUuid(), false, superseded);

        updateCard(evidence, request);
        return SlackInboundResponse.handled(null);
    }

    /** Resolve + re-authorize the evidence claim: must be the owner's. */
    static RecruitmentAvailabilityEvidence resolve(User actor, String evidenceUuid) {
        if (evidenceUuid == null || evidenceUuid.isBlank()) {
            return null;
        }
        RecruitmentAvailabilityEvidence evidence =
                RecruitmentAvailabilityEvidence.findById(evidenceUuid);
        if (evidence == null || !evidence.getUserUuid().equals(actor.getUuid())) {
            // Not-found and not-yours are indistinguishable on purpose.
            return null;
        }
        return evidence;
    }

    private void updateCard(RecruitmentAvailabilityEvidence evidence,
                            SlackInboundRequest request) {
        String channel = request.channelId() != null
                ? request.channelId() : evidence.getSlackChannelId();
        String ts = request.messageTs();
        if (channel == null || ts == null) {
            return;
        }
        List<RecruitmentAvailabilityConstraint> constraints =
                RecruitmentAvailabilityConstraint.list(
                        "evidenceUuid = ?1 order by startAt, endAt", evidence.getUuid());
        slackService.updateMessage(channel, ts,
                "Tilgængelighed bekræftet",
                SlackAvailabilityViews.confirmedCard(evidence, constraints));
    }
}
