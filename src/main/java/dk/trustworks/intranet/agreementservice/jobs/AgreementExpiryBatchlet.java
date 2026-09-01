package dk.trustworks.intranet.agreementservice.jobs;

import dk.trustworks.intranet.agreementservice.model.AgreementType;
import dk.trustworks.intranet.agreementservice.model.EmployeeAgreement;
import dk.trustworks.intranet.agreementservice.model.enums.AgreementStatus;
import dk.trustworks.intranet.agreementservice.services.AgreementsFeatureFlag;
import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Nightly agreement registry sweep (template-clauses spec §8, Phase 3):
 *
 * <ol>
 *   <li><b>Expiry flip</b> — ACTIVE rows past {@code valid_to} become
 *       EXPIRED. Always runs; state hygiene is not gated by the UI flag.</li>
 *   <li><b>Expiry alerts</b> — Slack posts to the configured HR channel
 *       ({@code agreements.slack.channel}) at 60 and 14 days before
 *       {@code valid_to}, idempotent via the {@code notified_60d_at} /
 *       {@code notified_14d_at} stamps (a stamp is only written after a
 *       successful post, so a Slack outage retries the next night).
 *       Alerts require {@code documents.agreements.enabled} — the message
 *       links a page that must exist for the reader.</li>
 * </ol>
 *
 * Scheduled daily via {@code BatchScheduler} with the
 * {@code SchedulerShutdownGuard} belt (the a4250c21 convention).
 */
@JBossLog
@Dependent
@Named("agreementExpiryBatchlet")
public class AgreementExpiryBatchlet extends MonitoredBatchlet {

    private static final DateTimeFormatter DANISH_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Inject
    AgreementsFeatureFlag featureFlag;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "dk.trustworks.agreements.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    @Transactional
    protected String doProcess() {
        LocalDate today = LocalDate.now();

        long expired = EmployeeAgreement.update(
                "status = ?1 WHERE status = ?2 AND validTo IS NOT NULL AND validTo < ?3",
                AgreementStatus.EXPIRED.name(), AgreementStatus.ACTIVE.name(), today);
        if (expired > 0) {
            log.infof("AgreementExpiryBatchlet: %d agreements flipped ACTIVE -> EXPIRED", expired);
        }

        int alerts60 = 0;
        int alerts14 = 0;
        String channel = featureFlag.slackChannel();
        if (!featureFlag.isEnabled() || channel == null) {
            log.debugf("Agreement expiry alerts skipped (enabled=%s, channel=%s)",
                    featureFlag.isEnabled(), channel);
            return summary(expired, alerts60, alerts14, "alerts-off");
        }

        List<EmployeeAgreement> upcoming = EmployeeAgreement.list(
                "status = ?1 AND validTo IS NOT NULL AND validTo >= ?2 AND validTo <= ?3"
                        + " AND (notified60dAt IS NULL OR notified14dAt IS NULL)"
                        + " ORDER BY validTo",
                AgreementStatus.ACTIVE.name(), today, today.plusDays(60));

        for (EmployeeAgreement agreement : upcoming) {
            AlertThreshold threshold = decideAlert(agreement.getValidTo(), today,
                    agreement.getNotified60dAt() != null, agreement.getNotified14dAt() != null);
            if (threshold == AlertThreshold.NONE) {
                continue;
            }
            try {
                slackService.sendMessage(channel, buildAlertMessage(agreement, threshold, today));
                LocalDateTime now = LocalDateTime.now();
                if (threshold == AlertThreshold.FOURTEEN_DAYS) {
                    // Inside 14 days both stamps are set — a row first seen
                    // here must not fire a late, redundant 60-day alert.
                    agreement.setNotified14dAt(now);
                    agreement.setNotified60dAt(now);
                    alerts14++;
                } else {
                    agreement.setNotified60dAt(now);
                    alerts60++;
                }
            } catch (Exception e) {
                // Stamp not written — the next nightly pass retries.
                log.errorf(e, "Agreement expiry alert failed for %s (channel %s)",
                        agreement.getUuid(), channel);
            }
        }

        return summary(expired, alerts60, alerts14, channel);
    }

    private static String summary(long expired, int alerts60, int alerts14, String channelInfo) {
        return String.format("COMPLETED: expired=%d, alerts60d=%d, alerts14d=%d, channel=%s",
                expired, alerts60, alerts14, channelInfo);
    }

    // ── Alert decision (pure — unit tested) ────────────────────────────────

    public enum AlertThreshold { NONE, SIXTY_DAYS, FOURTEEN_DAYS }

    /**
     * Which alert (if any) a row gets today. The 14-day alert wins inside
     * its window and covers the 60-day one; each threshold fires once,
     * enforced by the stamps.
     */
    public static AlertThreshold decideAlert(LocalDate validTo, LocalDate today,
                                             boolean sent60, boolean sent14) {
        long daysLeft = ChronoUnit.DAYS.between(today, validTo);
        if (daysLeft < 0) {
            return AlertThreshold.NONE; // the expiry flip owns past dates
        }
        if (daysLeft <= 14) {
            return sent14 ? AlertThreshold.NONE : AlertThreshold.FOURTEEN_DAYS;
        }
        if (daysLeft <= 60) {
            return sent60 ? AlertThreshold.NONE : AlertThreshold.SIXTY_DAYS;
        }
        return AlertThreshold.NONE;
    }

    // ── Message building ───────────────────────────────────────────────────

    private String buildAlertMessage(EmployeeAgreement agreement, AlertThreshold threshold,
                                     LocalDate today) {
        long daysLeft = ChronoUnit.DAYS.between(today, agreement.getValidTo());
        String subject = resolveSubjectName(agreement);
        AgreementType type = AgreementType.findById(agreement.getAgreementType());
        String typeName = type != null ? type.getName() : agreement.getAgreementType();
        String icon = threshold == AlertThreshold.FOURTEEN_DAYS ? ":warning:" : ":hourglass_flowing_sand:";
        String detail = agreement.getTitle() != null && !agreement.getTitle().equals(typeName)
                ? " (" + agreement.getTitle() + ")"
                : "";
        return String.format(
                "%s *%s*%s for %s udløber %s (om %d dage) — se detaljer: %s/hr/agreements",
                icon,
                typeName,
                detail,
                subject,
                agreement.getValidTo().format(DANISH_DATE),
                daysLeft,
                baseUrl);
    }

    private String resolveSubjectName(EmployeeAgreement agreement) {
        if (agreement.getUserUuid() != null) {
            return User.<User>findByIdOptional(agreement.getUserUuid())
                    .map(User::getFullname).orElse("ukendt medarbejder");
        }
        if (agreement.getCandidateUuid() != null) {
            RecruitmentCandidate candidate = RecruitmentCandidate.findById(agreement.getCandidateUuid());
            if (candidate != null) {
                return (candidate.getFirstName() + " " + candidate.getLastName()).trim();
            }
        }
        return "ukendt person";
    }

    @Override
    protected void onFinally(long executionId, String jobName) {
        log.debugf("Cleanup after job %s (execution %d)", jobName, executionId);
    }
}
