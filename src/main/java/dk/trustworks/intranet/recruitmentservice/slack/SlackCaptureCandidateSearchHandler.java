package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

import static com.slack.api.model.block.composition.BlockCompositions.option;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;

/**
 * The capture modal's candidate search (P14) — the {@code external_select}
 * options source, arriving as a {@code block_suggestion} payload (query-
 * only: never dedupe-claimed, no side effects). Results go through
 * {@link SlackCandidateSearch}, i.e. the exact P8 profile-read matrix —
 * the actor only ever sees candidates whose intranet profile they could
 * open. Anything else (including nonexistent names) is uniformly absent.
 */
@ApplicationScoped
public class SlackCaptureCandidateSearchHandler implements SlackInboundHandler {

    /** Slack renders ~10 options comfortably; more invites mis-picks. */
    static final int MAX_OPTIONS = 10;

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    SlackCandidateSearch candidateSearch;

    @Override
    public String key() {
        return SlackRecruitmentViews.CAPTURE_CANDIDATE_SELECT;
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!slackFlags.isCaptureEnabled()) {
            // The modal shouldn't be open with the flag off — answer an
            // empty option list rather than an error Slack can't render.
            return SlackInboundResponse.handledWithAction(SlackResponseActions.options(List.of()));
        }
        List<RecruitmentCandidate> hits =
                candidateSearch.search(actor.getUuid(), request.text(), MAX_OPTIONS);
        var options = new ArrayList<com.slack.api.model.block.composition.OptionObject>(hits.size());
        for (RecruitmentCandidate candidate : hits) {
            options.add(option(plainText(optionLabel(candidate)), candidate.getUuid()));
        }
        return SlackInboundResponse.handledWithAction(SlackResponseActions.options(options));
    }

    /**
     * "Jane Jensen (jane@example.com)" — the email disambiguates same-name
     * candidates; the caller is authorized to read the full profile, so
     * showing it here leaks nothing new. Plain text: no escaping needed.
     */
    private static String optionLabel(RecruitmentCandidate candidate) {
        String name = ((candidate.getFirstName() == null ? "" : candidate.getFirstName()) + " "
                + (candidate.getLastName() == null ? "" : candidate.getLastName())).trim();
        String label = candidate.getEmail() == null || candidate.getEmail().isBlank()
                ? name : name + " (" + candidate.getEmail() + ")";
        // Slack caps option labels at 75 chars.
        return label.length() <= 75 ? label : label.substring(0, 74) + "…";
    }
}
