package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Method B daily metrics line (plan §14, spec §30 subset) — one
 * structured INFO log per day, greppable by the {@code METHODB-METRICS}
 * token via the existing log conventions (CloudWatch Insights /
 * the log sweep), no new metrics infrastructure:
 * <pre>
 * METHODB-METRICS window=24h requests_active=3 holds_created=12 …
 * </pre>
 * Everything is derived from the audit events (the P12 idiom —
 * "event-derived bookkeeping") plus two live table counts; the job
 * writes nothing.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentSchedulingMetricsJob {

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    /** 05:40 UTC — after the nightly jobs, before the workday. */
    @Scheduled(cron = "0 40 5 * * ?", identity = "recruitment-scheduling-metrics",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledRun() {
        if (!methodBFlag.isMethodBEnabled()) {
            return;
        }
        try {
            log.info(QuarkusTransaction.requiringNew().call(this::buildLine));
        } catch (RuntimeException e) {
            log.errorf(e, "Method B metrics line failed");
        }
    }

    String buildLine() {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("requests_active", RecruitmentSchedulingRequest.count(
                "status not in ?1", RecruitmentSchedulingService.terminalStatuses()));
        counts.put("holds_created", eventCount(RecruitmentEventType.HOLD_CREATED, since));
        counts.put("holds_released", eventCount(RecruitmentEventType.HOLD_RELEASED, since));
        counts.put("holds_missing", eventCount(RecruitmentEventType.HOLD_MISSING, since));
        counts.put("slots_proposed", eventCount(RecruitmentEventType.SLOT_PROPOSED, since));
        counts.put("slots_rejected", eventCount(RecruitmentEventType.SLOT_REJECTED, since));
        counts.put("evidence_received",
                eventCount(RecruitmentEventType.AVAILABILITY_EVIDENCE_RECEIVED, since));
        counts.put("evidence_confirmed",
                eventCount(RecruitmentEventType.AVAILABILITY_EVIDENCE_CONFIRMED, since));
        counts.put("evidence_cancelled",
                eventCount(RecruitmentEventType.AVAILABILITY_EVIDENCE_CANCELLED, since));
        counts.put("images_deleted",
                eventCount(RecruitmentEventType.AVAILABILITY_IMAGE_DELETED, since));
        counts.put("ai_exchanges", eventCount(RecruitmentEventType.AI_SCHEDULING_EXCHANGE, since));
        counts.put("options_sent", eventCount(RecruitmentEventType.OPTIONS_SENT, since));
        counts.put("options_selected", eventCount(RecruitmentEventType.OPTION_SELECTED, since));
        counts.put("finalized", eventCount(RecruitmentEventType.SCHEDULING_FINALIZED, since));
        counts.put("handed_back", eventCount(RecruitmentEventType.SCHEDULING_HANDED_BACK, since));
        counts.put("expired", eventCount(RecruitmentEventType.SCHEDULING_EXPIRED, since));
        counts.put("outbox_failed_total", RecruitmentSchedulingOutbox.count(
                "status = ?1", SchedulingOutboxStatus.FAILED));

        // Cleanup-warning age (spec §21.5): how long the oldest FAILED
        // action has waited for a human.
        List<RecruitmentSchedulingOutbox> oldestFailed = RecruitmentSchedulingOutbox
                .<RecruitmentSchedulingOutbox>find("status = ?1 order by createdAt",
                        SchedulingOutboxStatus.FAILED)
                .page(0, 1).list();
        long oldestFailedHours = oldestFailed.isEmpty() ? 0
                : ChronoUnit.HOURS.between(oldestFailed.getFirst().getCreatedAt(),
                        LocalDateTime.now());

        StringBuilder line = new StringBuilder("METHODB-METRICS window=24h");
        counts.forEach((key, value) -> line.append(' ').append(key).append('=').append(value));
        line.append(" oldest_failed_hours=").append(oldestFailedHours);
        return line.toString();
    }

    private static long eventCount(RecruitmentEventType type, LocalDateTime since) {
        return RecruitmentEvent.count("eventType = ?1 and occurredAt >= ?2", type, since);
    }
}
