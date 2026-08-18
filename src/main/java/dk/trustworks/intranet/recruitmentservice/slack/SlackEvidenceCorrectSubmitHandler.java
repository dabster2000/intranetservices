package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityMessageService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.SchedulingAsyncRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * The Ret modal's submit: the interviewer edited the prefilled reading, so the
 * old reading is discarded and the edited text runs back through the ordinary
 * TEXT extraction as a fresh statement.
 * <p>
 * Why this exists. Until 2026-08-18 pressing Ret cancelled the evidence and
 * asked for a brand-new message, so correcting a misread week of a complex
 * client calendar meant retyping all of it while confirming a wrong reading
 * cost a single tap. That asymmetry, not the model, is what decided the data
 * quality actually reaching the planner: a busy interviewer confirms. The edit
 * path removes the retype without adding a single step to the happy path.
 * <p>
 * The edited text is deliberately fed to the TEXT extractor rather than being
 * parsed here: the prefill is rendered as absolute dates with explicit times,
 * which is the shape that extractor reads most reliably, and it keeps ONE
 * interpretation path instead of a second, subtly different parser.
 */
@JBossLog
@ApplicationScoped
public class SlackEvidenceCorrectSubmitHandler implements SlackInboundHandler {

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SchedulingAsyncRunner asyncRunner;

    @Inject
    AvailabilityMessageService messageService;

    @Inject
    SlackEvidenceCorrectHandler correctHandler;

    @Override
    public String key() {
        return SlackAvailabilityViews.CORRECT_SUBMIT;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!methodBFlag.isMethodBEnabled()) {
            return SlackInboundResponse.disabled(SlackInboundDispatchService.DISABLED_TEXT);
        }
        // private_metadata is a CLAIM — resolve() re-authorizes it against the
        // acting user, exactly as the button handler does.
        RecruitmentAvailabilityEvidence evidence =
                SlackEvidenceConfirmHandler.resolve(actor, request.privateMetadata());
        if (evidence == null) {
            return SlackInboundResponse.handled(SlackEvidenceConfirmHandler.GONE_TEXT);
        }
        boolean wasConfirmed =
                evidence.getConfirmationStatus() == EvidenceConfirmationStatus.CONFIRMED;
        if (evidence.getConfirmationStatus() != EvidenceConfirmationStatus.PENDING
                && !wasConfirmed) {
            return SlackInboundResponse.handled(SlackEvidenceConfirmHandler.GONE_TEXT);
        }
        boolean en = "en".equals(evidence.getLanguage());

        String corrected = SlackViewState.parse(objectMapper, request.stateValues())
                .text(SlackAvailabilityViews.BLOCK_CORRECTION);
        if (corrected == null) {
            return SlackInboundResponse.handledWithAction(
                    SlackResponseActions.errors(java.util.Map.of(
                            SlackAvailabilityViews.BLOCK_CORRECTION,
                            en ? "Write your availability first"
                                    : "Skriv din tilgængelighed først")));
        }

        // Discard the old reading first: the corrected statement replaces it,
        // and leaving both would double-count the same days.
        correctHandler.cancel(evidence, actor.getUuid());

        String actorUuid = actor.getUuid();
        String channelId = evidence.getSlackChannelId();
        String requestUuid = evidence.getRequestUuid();
        asyncRunner.submitAfterCommit(() ->
                messageService.processNote(actorUuid, channelId, requestUuid, corrected));

        return SlackInboundResponse.handled(en
                ? "Thanks — reading your correction now."
                : "Tak — jeg læser din rettelse nu.");
    }
}
