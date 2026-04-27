package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.application.integration.payloads.SlackDmPayload;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Interview;
import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Cron-driven scheduler that enqueues a Slack DM reminder to every participant
 * of every interview scheduled for "tomorrow" in Europe/Copenhagen local time.
 *
 * <p>Idempotency: keyed per (interview, recipient, target-date) — see
 * {@link OutboxIdempotencyKeys#slackInterviewTomorrow(String, String, LocalDate)}.
 * If an interview gets rescheduled into a different day after the first DM was
 * sent, a fresh row is enqueued for the new day; the original day's key has
 * already been consumed and is silently skipped.
 *
 * <p>Panache static seam: {@link #listScheduledInWindow(LocalDateTime, LocalDateTime)}
 * and {@link #listParticipants(String)} are package-private so unit tests can
 * override them — Mockito cannot stub statics inherited from {@code PanacheEntityBase}.
 */
@ApplicationScoped
public class InterviewTomorrowDmWorker {

    private static final Logger LOG = Logger.getLogger(InterviewTomorrowDmWorker.class);
    private static final ZoneId CET = ZoneId.of("Europe/Copenhagen");
    private static final DateTimeFormatter DAY_NAME = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH);
    private static final DateTimeFormatter HOUR_MIN = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    @Inject RecruitmentOutboxService outboxService;

    @ConfigProperty(name = "recruitment.deep-link-base")
    String deepLinkBase;

    /** Package-private + mutable so tests can install a fixed clock. */
    Clock clock = Clock.systemDefaultZone();

    @Scheduled(cron = "0 0 8 * * ?", timeZone = "Europe/Copenhagen")
    @Transactional
    public void enqueueTomorrowDms() {
        LocalDate tomorrowCet = LocalDate.now(clock.withZone(CET)).plusDays(1);
        LocalDateTime startUtc = tomorrowCet.atStartOfDay(CET).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime endUtc = tomorrowCet.plusDays(1).atStartOfDay(CET).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        List<Interview> interviews = listScheduledInWindow(startUtc, endUtc);
        LOG.debugf("InterviewTomorrowDmWorker: %d scheduled interview(s) found for %s",
                interviews.size(), tomorrowCet);

        for (Interview iv : interviews) {
            ZonedDateTime localStart = iv.scheduledAt.atZone(ZoneOffset.UTC).withZoneSameInstant(CET);
            String dayName = localStart.format(DAY_NAME);
            String time = localStart.format(HOUR_MIN);

            for (InterviewParticipant p : listParticipants(iv.uuid)) {
                String key = OutboxIdempotencyKeys.slackInterviewTomorrow(iv.uuid, p.userUuid, tomorrowCet);
                String headline = "Reminder: you have an interview tomorrow.";
                String body = String.format(
                        "🗓 %s %s · %s CET (%d min)",
                        dayName, tomorrowCet, time, iv.durationMinutes);
                String link = deepLinkBase + "/recruitment/interviews/" + iv.uuid;
                outboxService.enqueue(
                        OutboxKind.SLACK_INTERVIEW_TOMORROW_DM,
                        key,
                        iv.uuid,
                        new SlackDmPayload(p.userUuid, headline, body, link, key));
            }
        }
    }

    List<Interview> listScheduledInWindow(LocalDateTime startUtc, LocalDateTime endUtc) {
        return Interview.list("status = ?1 AND scheduledAt >= ?2 AND scheduledAt < ?3",
                InterviewStatus.SCHEDULED, startUtc, endUtc);
    }

    List<InterviewParticipant> listParticipants(String interviewUuid) {
        return InterviewParticipant.list("interviewUuid = ?1", interviewUuid);
    }
}
