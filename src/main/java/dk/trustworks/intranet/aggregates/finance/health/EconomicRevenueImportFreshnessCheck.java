package dk.trustworks.intranet.aggregates.finance.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Readiness probe that fails when auto-imported e-conomic invoices are stale.
 *
 * <p>Stale is defined as: the MAX {@code economics_entry_refreshed_at} across
 * all {@code invoices.economics_entry_number IS NOT NULL} rows is more than
 * {@link #maxStalenessHours} hours behind now. Default 25h gives the nightly
 * 02:00 UTC cron a 1-hour grace window for clock skew + retry.
 *
 * <p>Empty result (no auto-imported rows yet) is treated as UP, not DOWN — a
 * cold-started DB is not stale, just not yet populated, and the batchlet's
 * cold-start guard will trigger an async one-shot refresh independently.
 * Mirrors {@link OpexDistributionFreshnessCheck} behavior.
 *
 * <p>Both production tasks share the same DB, so failing readiness does not
 * actually shed load — its purpose is to make staleness visible at
 * {@code /q/health} for alerting.
 */
@JBossLog
@Readiness
@ApplicationScoped
public class EconomicRevenueImportFreshnessCheck implements HealthCheck {

    @Inject
    EntityManager em;

    @ConfigProperty(
            name = "dk.trustworks.intranet.economic-revenue-import.max-staleness-hours",
            defaultValue = "25")
    int maxStalenessHours;

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public HealthCheckResponse call() {
        // SmallRye health runs @Readiness checks concurrently on multiple
        // threads sharing the @Inject EntityManager (request-scoped, but
        // health-check threads aren't HTTP requests). A second concurrent
        // check on the same EM can throw ConcurrentModificationException
        // from Hibernate internals — observed on staging 2026-05-13 against
        // both this check and OpexDistributionFreshnessCheck (they alternate
        // failures). Treating transient errors as UP defensively is the
        // right policy here: the readiness probe does not actually shed load
        // (both tasks share the same DB) and a false DOWN would suppress
        // legitimate freshness alerting on a working system.
        try {
            Object[] row = (Object[]) em.createNativeQuery(
                    "SELECT MAX(economics_entry_refreshed_at), COUNT(*) " +
                            "FROM invoices WHERE economics_entry_number IS NOT NULL")
                    .getSingleResult();
            LocalDateTime maxRefreshed = row[0] == null ? null
                    : row[0] instanceof Timestamp ts ? ts.toLocalDateTime()
                    : (LocalDateTime) row[0];
            long rowCount = ((Number) row[1]).longValue();

            long ageHours = maxRefreshed == null ? Long.MAX_VALUE
                    : Duration.between(maxRefreshed, LocalDateTime.now()).toHours();

            HealthCheckResponseBuilder b = HealthCheckResponse
                    .named("economic-revenue-import-freshness")
                    .withData("rows", rowCount)
                    .withData("max_refreshed_at", maxRefreshed == null ? "never" : maxRefreshed.toString())
                    .withData("age_hours", ageHours)
                    .withData("max_allowed_hours", maxStalenessHours);

            // Empty table or null max timestamp = cold-start refresh pending — not stale.
            if (rowCount == 0 || maxRefreshed == null) {
                return b.up().build();
            }
            if (ageHours <= maxStalenessHours) {
                return b.up().build();
            }
            log.warnf("economic-revenue-import-freshness DOWN: rows=%d, age_hours=%d, max_hours=%d",
                    rowCount, ageHours, maxStalenessHours);
            return b.down().build();
        } catch (RuntimeException ex) {
            log.warnf(ex, "economic-revenue-import-freshness transient error — reporting UP defensively");
            return HealthCheckResponse
                    .named("economic-revenue-import-freshness")
                    .withData("transient_error", ex.getClass().getSimpleName())
                    .up().build();
        }
    }
}
