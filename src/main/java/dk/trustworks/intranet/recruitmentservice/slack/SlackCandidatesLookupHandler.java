package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCandidateFacts;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * {@code /candidates <name>} — the ephemeral status lookup (P14, flag
 * {@code recruitment.slack.lookup.enabled}). The result card carries
 * name, open position(s) with stage and days-in-stage, and a profile
 * deep link — nothing more (deliberately no referrer identity, no
 * scores, no notes: it is a WHERE-question surface, spec §5.5).
 * <p>
 * Authorization IS the search: {@link SlackCandidateSearch} filters
 * through the exact P8 profile-read matrix, and the pipeline lines are
 * additionally filtered per application
 * ({@link RecruitmentVisibility#filterApplications} — a non-circle
 * viewer never sees a partner-track application even on a candidate
 * visible through another application). No access and no match answer
 * the SAME sentence — existence never leaks. Ephemeral = nothing
 * persists in channel history.
 */
@ApplicationScoped
public class SlackCandidatesLookupHandler implements SlackInboundHandler {

    static final int MAX_RESULTS = 5;

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    SlackCandidateSearch candidateSearch;

    @Inject
    RecruitmentVisibility visibility;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    public String key() {
        return "/candidates";
    }

    @Override
    public SlackInboundResponse handle(User actor, SlackInboundRequest request) {
        if (!slackFlags.isLookupEnabled()) {
            return SlackInboundResponse.disabled(SlackInboundDispatchService.DISABLED_TEXT);
        }
        String query = request.text() == null ? "" : request.text().trim();
        if (query.length() < 2) {
            return SlackInboundResponse.handled(
                    "Type a name after the command — for example `/candidates jane`. "
                            + "You'll get a private card with status and a profile link, "
                            + "visible only to you.");
        }
        List<RecruitmentCandidate> hits =
                candidateSearch.search(actor.getUuid(), query, MAX_RESULTS);
        String safeQuery = SlackCandidateFacts.mrkdwnSafe(query);
        if (hits.isEmpty()) {
            // Uniform for "doesn't exist" and "not yours to see".
            return SlackInboundResponse.handled(
                    "No candidates matching \"" + safeQuery + "\" that you have access to.");
        }
        StringBuilder sb = new StringBuilder(256)
                .append("Candidates matching \"").append(safeQuery)
                .append("\" — visible only to you:");
        for (RecruitmentCandidate candidate : hits) {
            sb.append("\n\n").append(candidateCard(actor.getUuid(), candidate));
        }
        return SlackInboundResponse.handled(clamp(sb.toString()));
    }

    private String candidateCard(String actorUuid, RecruitmentCandidate candidate) {
        String name = ((candidate.getFirstName() == null ? "" : candidate.getFirstName()) + " "
                + (candidate.getLastName() == null ? "" : candidate.getLastName())).trim();
        StringBuilder sb = new StringBuilder(128)
                .append("*").append(SlackCandidateFacts.mrkdwnSafe(name)).append("*");

        // Per-application visibility on top of profile access (P4 filter).
        List<RecruitmentApplication> applications =
                visibility.filterApplications(actorUuid, candidate.getUuid());
        List<RecruitmentApplication> open = applications.stream()
                .filter(a -> a.getTerminal() == null)
                .toList();
        if (open.isEmpty()) {
            sb.append(" — ").append(statusLine(candidate, !applications.isEmpty()));
        } else {
            for (RecruitmentApplication application : open) {
                RecruitmentPosition position =
                        RecruitmentPosition.findById(application.getPositionUuid());
                sb.append("\n• ")
                        .append(position == null ? "Unknown position"
                                : "*" + SlackCandidateFacts.mrkdwnSafe(position.getTitle()) + "*")
                        .append(": ").append(SlackHandlerSupport.humanizeStage(
                                application.getStage().name()))
                        .append(" · ").append(daysInStage(application.getStageEnteredAt()))
                        .append(" in stage");
            }
        }
        sb.append("\n<").append(baseUrl).append("/recruitment/candidates/")
                .append(candidate.getUuid()).append("|Open profile>");
        return sb.toString();
    }

    private static String statusLine(RecruitmentCandidate candidate, boolean hadApplications) {
        return switch (candidate.getStatus()) {
            case POOLED -> "in the talent pool";
            case HIRED -> "hired";
            case DECLINED -> "not proceeding (declined)";
            case WITHDRAWN -> "withdrew";
            default -> hadApplications ? "no open applications" : "no applications yet";
        };
    }

    private static String daysInStage(LocalDateTime stageEnteredAt) {
        if (stageEnteredAt == null) {
            return "? days";
        }
        long days = ChronoUnit.DAYS.between(stageEnteredAt, LocalDateTime.now(ZoneOffset.UTC));
        if (days <= 0) {
            return "today";
        }
        return days == 1 ? "1 day" : days + " days";
    }

    /** The module's Slack text clamp (P12) — a shorter card beats a lost one. */
    private static String clamp(String message) {
        return message.length() <= 3000 ? message : message.substring(0, 2999) + "…";
    }
}
