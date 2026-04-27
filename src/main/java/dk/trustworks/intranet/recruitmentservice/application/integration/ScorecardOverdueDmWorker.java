package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.SlackDmPayload;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Scorecard;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cron-driven scheduler that enqueues overdue-scorecard Slack DMs to required
 * scorers who haven't yet submitted, on a two-stage cadence:
 *
 * <ul>
 *   <li>Stage&nbsp;1 — interview held ≥ 24h ago: gentle reminder.</li>
 *   <li>Stage&nbsp;2 — interview held ≥ 48h ago: escalated message; the round-up
 *       cannot complete until all scorecards are in.</li>
 * </ul>
 *
 * <p>"Submitted" is determined by the existence of a {@link Scorecard} row whose
 * {@code interviewerUserUuid} matches the participant's {@code userUuid} —
 * {@code InterviewParticipant.scorecardSubmittedAt} does not exist in this slice;
 * the {@code recruitment_scorecard} table is the source of truth.
 *
 * <p>Idempotency: keyed per (interview, scorer, stage) — so each scorer can be
 * pinged at most once at stage 1 and once at stage 2 per interview. See
 * {@link OutboxIdempotencyKeys#slackScorecardOverdue(String, String, int)}.
 *
 * <p>Panache static seam: {@link #listHeldOverdue(LocalDateTime)},
 * {@link #listRequiredScorers(String)}, and {@link #listScorecardSubmitterUuids(String)}
 * are package-private so unit tests can override them.
 */
@ApplicationScoped
public class ScorecardOverdueDmWorker {

    private static final Logger LOG = Logger.getLogger(ScorecardOverdueDmWorker.class);

    @Inject RecruitmentOutboxService outboxService;

    @ConfigProperty(name = "recruitment.deep-link-base")
    String deepLinkBase;

    /** UTC clock — all the threshold math is in UTC; tests install a fixed clock. */
    Clock clock = Clock.systemUTC();

    @Scheduled(cron = "0 0 8 * * ?", timeZone = "Europe/Copenhagen")
    @Transactional
    public void enqueueOverdueDms() {
        LocalDateTime nowUtc = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        LocalDateTime cutoff24h = nowUtc.minusHours(24);
        LocalDateTime cutoff48h = nowUtc.minusHours(48);

        List<Interview> overdue = listHeldOverdue(cutoff24h);
        LOG.debugf("ScorecardOverdueDmWorker: %d HELD interview(s) past 24h cutoff", overdue.size());

        for (Interview iv : overdue) {
            int stage = (iv.heldAt != null && iv.heldAt.isBefore(cutoff48h)) ? 2 : 1;
            Set<String> submitters = listScorecardSubmitterUuids(iv.uuid);

            for (InterviewParticipant scorer : listRequiredScorers(iv.uuid)) {
                if (submitters.contains(scorer.userUuid)) {
                    continue;
                }
                String key = OutboxIdempotencyKeys.slackScorecardOverdue(iv.uuid, scorer.userUuid, stage);
                String headline = stage == 2
                        ? "Overdue: scorecard >48h"
                        : "Reminder: scorecard due";
                long hoursAge = iv.heldAt != null
                        ? Duration.between(iv.heldAt, nowUtc).toHours()
                        : 0L;
                String body = stage == 2
                        ? String.format(
                                "Your scorecard for the recent interview is now %dh overdue and is blocking the round-up. Please submit today.",
                                hoursAge)
                        : "Your scorecard for the recent interview is due. Submit when you can.";
                String link = deepLinkBase + "/recruitment/interviews/" + iv.uuid;
                outboxService.enqueue(
                        OutboxKind.SLACK_SCORECARD_OVERDUE_DM,
                        key,
                        iv.uuid,
                        new SlackDmPayload(scorer.userUuid, headline, body, link, key));
            }
        }
    }

    List<Interview> listHeldOverdue(LocalDateTime cutoff24h) {
        return Interview.list("status = ?1 AND heldAt <= ?2", InterviewStatus.HELD, cutoff24h);
    }

    List<InterviewParticipant> listRequiredScorers(String interviewUuid) {
        return InterviewParticipant.list("interviewUuid = ?1 and isRequiredScorer = true", interviewUuid);
    }

    Set<String> listScorecardSubmitterUuids(String interviewUuid) {
        return Scorecard.<Scorecard>list("interviewUuid = ?1", interviewUuid).stream()
                .map(s -> s.interviewerUserUuid)
                .collect(Collectors.toSet());
    }
}
