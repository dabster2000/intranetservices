package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.ScorecardSubmitRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.enums.ScorecardRecommendation;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentInterviewService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The scorecard modal's {@code view_submission} (P18, Slack spec §5.6):
 * executes the SAME command as the web form —
 * {@link RecruitmentInterviewService#submitScorecard} with
 * {@code origin='slack'} — so assignment enforcement, the one-per-interviewer
 * rule, template validation, HELD marking and the blind rule apply
 * unchanged. On success the modal swaps to a confirmation; when the last
 * scorecard lands, the P12 reactor's debrief-ready notification (deep link,
 * no decision buttons) fires off the {@code SCORECARD_SUBMITTED} event.
 * <p>
 * {@code private_metadata} carries the interview uuid — a CLAIM; the
 * service re-verifies the actor is an assigned interviewer who hasn't
 * submitted. Validation failures render inline
 * ({@code response_action: errors}); business conflicts swap to a terminal
 * outcome view. Either way the P14 rollback semantics apply: a failed
 * attempt leaves zero rows and an unburned payload id.
 * <p>
 * Idempotency (DoD): a duplicate submission payload dies in the P13
 * dedupe; a genuinely repeated attempt conflicts on the one-per-interviewer
 * rule and renders the already-submitted view — one command execution, one
 * event, always.
 */
@JBossLog
@ApplicationScoped
public class SlackScorecardSubmitHandler implements SlackInboundHandler {

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    RecruitmentInterviewService interviewService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    public String key() {
        return SlackRecruitmentViews.SCORECARD_SUBMIT;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!slackFlags.isScorecardEnabled()) {
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Feature disabled",
                            SlackInboundDispatchService.DISABLED_TEXT)));
        }
        RecruitmentInterview interview = resolveInterview(request.privateMetadata());
        if (interview == null) {
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Something went wrong",
                            "The form lost track of its interview — close this and click "
                                    + "the scorecard button on the reminder again.")));
        }
        RecruitmentApplication application =
                RecruitmentApplication.findById(interview.getApplicationUuid());
        RecruitmentPosition position = application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
        if (application == null || position == null) {
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Not available",
                            SlackScorecardOpenButtonHandler.NOT_AVAILABLE_TEXT)));
        }
        List<ScorecardAttribute> template = position.getScorecardTemplate();
        SlackViewState state = SlackViewState.parse(objectMapper, request.stateValues());
        ScorecardSubmitRequest submit = new ScorecardSubmitRequest(
                scoresFrom(state, template),
                recommendationFrom(state),
                state.text(SlackRecruitmentViews.BLOCK_SCORECARD_NOTES));

        try {
            interviewService.submitScorecard(interview, application, position, submit,
                    UUID.fromString(actor.getUuid()), RecruitmentInterviewService.ORIGIN_SLACK);
        } catch (BusinessRuleViolation e) {
            return deny(e.getMessage(), template);
        }

        // Progress for the confirmation — counters only, never content
        // (the same numbers the web surfaces show while blind).
        List<RecruitmentScorecard> all = RecruitmentScorecard.list(
                "interviewUuid = ?1", interview.getUuid());
        int missing = Math.max(0, interview.getInterviewerUuids().size() - all.size());
        RecruitmentCandidate candidate =
                RecruitmentCandidate.findById(application.getCandidateUuid());
        return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                SlackRecruitmentViews.scorecardSubmittedView(
                        SlackScorecardOpenButtonHandler.candidateName(candidate),
                        missing, baseUrl)));
    }

    /**
     * Maps the service's re-validation message to the right response: the
     * one-per-interviewer conflict and assignment/state denials become
     * terminal outcome views (the form can never submit); score/
     * recommendation/notes validation anchors inline to the offending block
     * ({@code response_action: errors}). Slack enforces required inputs
     * client-side, so the inline path fires mainly on forged or stale
     * payloads — e.g. a template edited between open and submit.
     */
    private SlackInboundResponse deny(String message, List<ScorecardAttribute> template) {
        String text = message == null ? "Invalid input" : message;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("already submitted")) {
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Already submitted",
                            SlackScorecardOpenButtonHandler.ALREADY_SUBMITTED_TEXT)));
        }
        if (lower.contains("assigned interviewers")
                || lower.contains("cancelled")
                || lower.contains("informal")
                || lower.contains("no scorecard template")
                || lower.contains("no longer exists")) {
            return SlackInboundResponse.handledWithAction(SlackResponseActions.update(
                    SlackRecruitmentViews.outcomeView("Not available",
                            SlackScorecardOpenButtonHandler.NOT_AVAILABLE_TEXT)));
        }
        return SlackInboundResponse.handledWithAction(SlackResponseActions.errors(
                SlackRecruitmentViews.errorOn(errorBlockFor(text, template), text)));
    }

    /** The input block a validation message anchors to (label → score block). */
    private static String errorBlockFor(String message, List<ScorecardAttribute> template) {
        if (template != null) {
            for (ScorecardAttribute attribute : template) {
                if (message.contains("'" + attribute.label() + "'")) {
                    return SlackRecruitmentViews.BLOCK_SCORE_PREFIX + attribute.code();
                }
            }
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("recommendation")) {
            return SlackRecruitmentViews.BLOCK_RECOMMENDATION;
        }
        if (lower.contains("notes")) {
            return SlackRecruitmentViews.BLOCK_SCORECARD_NOTES;
        }
        if (template != null && !template.isEmpty()) {
            return SlackRecruitmentViews.BLOCK_SCORE_PREFIX + template.getFirst().code();
        }
        return SlackRecruitmentViews.BLOCK_RECOMMENDATION;
    }

    /**
     * Collects the {@code score_<CODE>} selects into the command's score
     * map. Unparseable values are left out — the service's template
     * validation reports the missing attribute by label, which renders
     * inline on the right block.
     */
    private static Map<String, Integer> scoresFrom(SlackViewState state,
                                                   List<ScorecardAttribute> template) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        if (template == null) {
            return scores;
        }
        for (ScorecardAttribute attribute : template) {
            String selected = state.selected(
                    SlackRecruitmentViews.BLOCK_SCORE_PREFIX + attribute.code());
            if (selected == null) {
                continue;
            }
            try {
                scores.put(attribute.code(), Integer.parseInt(selected.trim()));
            } catch (NumberFormatException e) {
                // Forged option value — skip; validation names the gap.
            }
        }
        return scores;
    }

    private static ScorecardRecommendation recommendationFrom(SlackViewState state) {
        String selected = state.selected(SlackRecruitmentViews.BLOCK_RECOMMENDATION);
        if (selected == null) {
            return null;
        }
        try {
            return ScorecardRecommendation.valueOf(selected.trim());
        } catch (IllegalArgumentException e) {
            return null; // forged radio value — the service reports it missing
        }
    }

    private static RecruitmentInterview resolveInterview(String privateMetadata) {
        if (privateMetadata == null || privateMetadata.isBlank()) {
            return null;
        }
        try {
            UUID.fromString(privateMetadata.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return RecruitmentInterview.findById(privateMetadata.trim());
    }
}
