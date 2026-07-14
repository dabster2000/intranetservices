package dk.trustworks.intranet.aggregates.utilization.services;

import dk.trustworks.intranet.aggregates.utilization.dto.ActualDataStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.CXO_QUERY_TIMEOUT_MS;

/**
 * Resolves the last pipeline-certified complete date for {@code fact_user_day} actuals.
 * Row-level {@code last_update} values are deliberately not used: availability is updated
 * before work aggregation, so those timestamps cannot prove that the full pipeline completed.
 */
@ApplicationScoped
@JBossLog
public class FactUserDayFreshnessService {

    static final String PIPELINE_NAME = "FACT_USER_DAY";
    static final String WATERMARK_SQL = """
            SELECT certified_complete_through_date,
                   last_full_refresh_at,
                   last_incremental_refresh_at,
                   refresh_state
            FROM bi_refresh_watermark
            WHERE pipeline_name = :pipelineName
            """;

    @Inject
    EntityManager em;

    public Freshness resolve() {
        return resolve(reportingDate(Instant.now()));
    }

    /** Visible for deterministic service callers and tests. */
    public Freshness resolve(LocalDate copenhagenToday) {
        Query query = em.createNativeQuery(WATERMARK_SQL);
        query.setParameter("pipelineName", PIPELINE_NAME);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        if (rows.isEmpty()) {
            return evaluate(copenhagenToday, null, null, null);
        }

        Object[] row = rows.getFirst();
        return evaluate(
                copenhagenToday,
                toLocalDate(row[0]),
                latest(toInstant(row[1]), toInstant(row[2])),
                row[3] == null ? null : String.valueOf(row[3])
        );
    }

    static Freshness evaluate(
            LocalDate copenhagenToday,
            LocalDate certifiedCompleteThroughDate,
            Instant sourceRefreshedAt,
            String refreshState) {
        LocalDate requested = copenhagenToday.minusDays(1);
        boolean ready = "READY".equalsIgnoreCase(refreshState);
        if (!ready || certifiedCompleteThroughDate == null) {
            return new Freshness(
                    requested, null, ActualDataStatus.UNAVAILABLE, null, sourceRefreshedAt);
        }

        LocalDate effective;
        if (certifiedCompleteThroughDate.isAfter(requested)) {
            log.warnf("Capping future %s watermark %s to requested cutoff %s",
                    PIPELINE_NAME, certifiedCompleteThroughDate, requested);
            effective = requested;
        } else {
            effective = certifiedCompleteThroughDate;
        }
        int lagDays = Math.toIntExact(ChronoUnit.DAYS.between(effective, requested));
        ActualDataStatus status = lagDays == 0
                ? ActualDataStatus.COMPLETE
                : ActualDataStatus.SOURCE_LAGGED;
        return new Freshness(requested, effective, status, lagDays, sourceRefreshedAt);
    }

    static LocalDate reportingDate(Instant instant) {
        return instant.atZone(UtilizationCalculationHelper.REPORTING_ZONE).toLocalDate();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        if (value instanceof Date date) return date.toLocalDate();
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        return Instant.parse(String.valueOf(value));
    }

    private static Instant latest(Instant first, Instant second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    public record Freshness(
            LocalDate requestedActualThroughDate,
            LocalDate actualDataThroughDate,
            ActualDataStatus actualDataStatus,
            Integer actualSourceLagDays,
            Instant sourceRefreshedAt) {

        public boolean hasCertifiedActualData() {
            return actualDataThroughDate != null;
        }
    }
}
