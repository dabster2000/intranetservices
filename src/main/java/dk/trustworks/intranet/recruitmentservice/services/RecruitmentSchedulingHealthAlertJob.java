package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceSourceType;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Method B ops watchdog (plan §14, the FactChangeLog alert idiom):
 * every 15 minutes, four checks — each a way the pipeline can rot
 * silently because everything is retries and sweeps:
 * <ol>
 *   <li><b>FINALIZING stall</b> — the candidate confirmed but no meeting
 *       exists after an hour: the saga is wedged (Graph down, rule
 *       rejection loop). The single worst state to sit in.</li>
 *   <li><b>Sweep starvation</b> — a non-terminal request whose
 *       {@code nextActionAt} is hours past due: the advance sweep is
 *       erroring on it every minute (the per-request catch swallows).</li>
 *   <li><b>Outbox dead-letters</b> — FAILED external writes (spec §21.5's
 *       cleanup warnings) waiting for a human.</li>
 *   <li><b>Extraction/deletion rot</b> — a burst of REJECTED evidence in
 *       the last hour (model down or a prompt regression, plan §14
 *       "extraction error bursts"), or evidence images still in S3 a day
 *       after leaving PENDING (the D10 deletion path failing; the 7-day
 *       lifecycle rule is the last net, not the plan).</li>
 * </ol>
 * One Slack message to #ops-alert lists every breached check; while a
 * breach persists the channel hears at most one message per hour, and
 * the window resets when everything clears (the house idiom).
 */
@JBossLog
@ApplicationScoped
public class RecruitmentSchedulingHealthAlertJob {

    static final Duration ALERT_REPEAT_INTERVAL = Duration.ofMinutes(60);

    /** FINALIZING should finish within a couple of sweeps. */
    static final int FINALIZING_STALL_MINUTES = 60;

    /** nextActionAt this far past due ⇒ the sweep keeps failing on it. */
    static final int SWEEP_STARVED_HOURS = 2;

    /** Images should shed within one deletion sweep, not a day. */
    static final int IMAGE_DELETION_OVERDUE_HOURS = 24;

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    @ConfigProperty(name = "dk.trustworks.recruitment.scheduling.alerts.rejected-burst-threshold",
            defaultValue = "5")
    long rejectedBurstThreshold;

    private final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    @Scheduled(cron = "0 */15 * * * ?", identity = "recruitment-scheduling-health-alert")
    void scheduledRun() {
        if (!methodBFlag.isMethodBEnabled()) {
            return;
        }
        try {
            checkHealth();
        } catch (RuntimeException e) {
            log.errorf(e, "Method B health check failed");
        }
    }

    void checkHealth() {
        List<String> breaches = QuarkusTransaction.requiringNew().call(this::collectBreaches);
        if (breaches.isEmpty()) {
            lastAlertSent.set(null);
            return;
        }
        Instant now = Instant.now();
        Instant previous = lastAlertSent.get();
        if (previous != null
                && Duration.between(previous, now).compareTo(ALERT_REPEAT_INTERVAL) < 0) {
            log.debugf("Method B health still breached — suppressing duplicate alert");
            return;
        }
        String message = ":rotating_light: *Method B interview scheduling needs a look*\n• "
                + String.join("\n• ", breaches);
        log.warnf("Method B health alert firing: %s", String.join(" | ", breaches));
        slackService.sendMessage(opsAlertChannel, message, "mother");
        lastAlertSent.set(now);
    }

    private List<String> collectBreaches() {
        LocalDateTime now = LocalDateTime.now();
        List<String> breaches = new ArrayList<>();

        long stalledFinalizing = dk.trustworks.intranet.recruitmentservice.model
                .RecruitmentSchedulingRequest.count(
                        "status = ?1 and updatedAt <= ?2",
                        SchedulingRequestStatus.FINALIZING,
                        now.minusMinutes(FINALIZING_STALL_MINUTES));
        if (stalledFinalizing > 0) {
            breaches.add(stalledFinalizing + " request(s) stuck FINALIZING > "
                    + FINALIZING_STALL_MINUTES + " min — candidate chose, no meeting exists yet");
        }

        long starved = dk.trustworks.intranet.recruitmentservice.model
                .RecruitmentSchedulingRequest.count(
                        "status not in ?1 and nextActionAt is not null and nextActionAt <= ?2",
                        RecruitmentSchedulingService.terminalStatuses(),
                        now.minusHours(SWEEP_STARVED_HOURS));
        if (starved > 0) {
            breaches.add(starved + " request(s) with nextActionAt > " + SWEEP_STARVED_HOURS
                    + " h overdue — the advance sweep is failing on them (check the warn log)");
        }

        long deadLetters = dk.trustworks.intranet.recruitmentservice.model
                .RecruitmentSchedulingOutbox.count("status = ?1", SchedulingOutboxStatus.FAILED);
        if (deadLetters > 0) {
            breaches.add(deadLetters + " dead-lettered outbox action(s) — holds/DMs/deletions "
                    + "an operator must finish by hand (spec §21.5)");
        }

        long rejectedBurst = dk.trustworks.intranet.recruitmentservice.model
                .RecruitmentAvailabilityEvidence.count(
                        "confirmationStatus = ?1 and createdAt >= ?2",
                        EvidenceConfirmationStatus.REJECTED, now.minusHours(1));
        if (rejectedBurst >= rejectedBurstThreshold) {
            breaches.add(rejectedBurst + " REJECTED extractions in the last hour (threshold "
                    + rejectedBurstThreshold + ") — model outage or prompt regression");
        }

        long imagesOverdue = dk.trustworks.intranet.recruitmentservice.model
                .RecruitmentAvailabilityEvidence.count(
                        "sourceType = ?1 and fileSha256 is not null and s3DeletedAt is null "
                                + "and confirmationStatus != ?2 and updatedAt <= ?3",
                        EvidenceSourceType.IMAGE, EvidenceConfirmationStatus.PENDING,
                        now.minusHours(IMAGE_DELETION_OVERDUE_HOURS));
        if (imagesOverdue > 0) {
            breaches.add(imagesOverdue + " evidence image(s) still in S3 > "
                    + IMAGE_DELETION_OVERDUE_HOURS + " h after leaving PENDING — "
                    + "the D10 deletion path is failing");
        }
        return breaches;
    }
}
