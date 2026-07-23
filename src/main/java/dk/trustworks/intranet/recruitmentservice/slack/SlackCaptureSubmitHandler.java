package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.NoteRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.CandidateService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

/**
 * The capture modal's {@code view_submission} (P14): appends the edited
 * message text as a {@code NOTE_ADDED} event — text exclusively in
 * {@code pii} (P3 note discipline), {@code payload.origin='slack'} and
 * {@code payload.slack_permalink} for provenance, the private flag
 * feeding the P8 timeline's author+recruiter+admin gate.
 * <p>
 * The candidate id arrives from the modal's select — a CLAIM: the actor
 * is re-authorized against the P8 profile-read matrix before the note
 * lands, and an unauthorized or unknown candidate answers the same
 * uniform inline error (no existence disclosure).
 */
@JBossLog
@ApplicationScoped
public class SlackCaptureSubmitHandler implements SlackInboundHandler {

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    CandidateService candidateService;

    @Inject
    SlackService slackService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    public String key() {
        return SlackRecruitmentViews.CAPTURE_SUBMIT;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!slackFlags.isCaptureEnabled()) {
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Feature disabled",
                            SlackInboundDispatchService.DISABLED_TEXT)));
        }
        SlackViewState state = SlackViewState.parse(objectMapper, request.stateValues());
        String candidateUuid = state.selected(SlackRecruitmentViews.BLOCK_CAPTURE_CANDIDATE);
        String noteText = state.text(SlackRecruitmentViews.BLOCK_NOTE_TEXT);
        boolean isPrivate = state.checked(SlackRecruitmentViews.BLOCK_NOTE_PRIVATE,
                SlackRecruitmentViews.PRIVATE_OPTION);

        if (noteText == null || noteText.isBlank()) {
            return SlackInboundResponse.handledWithAction(SlackResponseActions.errors(
                    SlackRecruitmentViews.errorOn(SlackRecruitmentViews.BLOCK_NOTE_TEXT,
                            "The note can't be empty.")));
        }
        RecruitmentCandidate candidate = candidateUuid == null ? null
                : RecruitmentCandidate.findById(candidateUuid);
        if (candidate == null || !visibility.canReadCandidateProfile(actor.getUuid(), candidate)) {
            // Uniform for unknown AND unauthorized — existence never leaks.
            return SlackInboundResponse.handledWithAction(SlackResponseActions.errors(
                    SlackRecruitmentViews.errorOn(SlackRecruitmentViews.BLOCK_CAPTURE_CANDIDATE,
                            "Pick a candidate from the list — you can only log to candidates "
                                    + "you have access to.")));
        }

        // Provenance permalink — best-effort: the bot can't see private
        // conversations it isn't in; the note still lands without it.
        SlackHandlerSupport.ModalMetadata metadata =
                SlackHandlerSupport.ModalMetadata.parse(objectMapper, request.privateMetadata());
        String permalink = metadata.hasValidPingRef()
                ? slackService.getPermalink(metadata.channelId(), metadata.messageTs())
                : null;

        candidateService.addNote(
                UUID.fromString(candidate.getUuid()),
                new NoteRequest(noteText, isPrivate, null),
                UUID.fromString(actor.getUuid()),
                "slack", permalink);

        String candidateName = ((candidate.getFirstName() == null ? "" : candidate.getFirstName())
                + " " + (candidate.getLastName() == null ? "" : candidate.getLastName())).trim();
        return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                SlackRecruitmentViews.noteSavedView(
                        candidateName, candidate.getUuid(), baseUrl, isPrivate)));
    }
}
