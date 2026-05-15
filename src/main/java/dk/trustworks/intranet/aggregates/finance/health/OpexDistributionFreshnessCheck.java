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
 * Readiness probe that fails when fact_opex_distribution_mat is empty or stale.
 *
 * <p>Stale is defined as: oldest row's refreshed_at is more than {@link #maxStalenessHours}
 * hours behind now. Default 30h gives the nightly 03:30 UTC refresh a 6-hour grace window
 * before alerting.
 *
 * <p>Both production tasks share the same DB, so failing readiness does not actually
 * shed load — its purpose is to make staleness visible at /q/health for alerting.
 */
@JBossLog
@Readiness
@ApplicationScoped
public class OpexDistributionFreshnessCheck implements HealthCheck {

    @Inject
    EntityManager em;

    @ConfigProperty(name = "dk.trustworks.intranet.opex-distribution.max-staleness-hours", defaultValue = "30")
    int maxStalenessHours;

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public HealthCheckResponse call() {
        // SmallRye health runs @Readiness checks concurrently on multiple
        // threads sharing the @Inject EntityManager (request-scoped, but
        // health-check threads aren't HTTP requests). A second concurrent
        // check on the same EM can throw ConcurrentModificationException
        // from Hibernate internals. EconomicRevenueImportFreshnessCheck
        // received this defensive pattern on 2026-05-13; this check was
        // missed and caused production tasks to fail the canary health
        // probe on 2026-05-15 (uncaught CME bubbled to SmallRye → overall
        // status DOWN → ECS Express deprovisioned new tasks). Treating
        // transient errors as UP is the right policy here: the readiness
        // probe does not actually shed load (both tasks share the same
        // DB) and a false DOWN suppresses legitimate freshness alerting
        // on a working system.
        try {
            Object[] row = (Object[]) em.createNativeQuery(
                    "SELECT MIN(refreshed_at), COUNT(*) FROM fact_opex_distribution_mat")
                    .getSingleResult();
            LocalDateTime oldest = row[0] == null ? null
                    : row[0] instanceof Timestamp ts ? ts.toLocalDateTime()
                    : (LocalDateTime) row[0];
            long rowCount = ((Number) row[1]).longValue();

            long ageHours = oldest == null ? Long.MAX_VALUE
                    : Duration.between(oldest, LocalDateTime.now()).toHours();

            HealthCheckResponseBuilder b = HealthCheckResponse
                    .named("opex-distribution-freshness")
                    .withData("rows", rowCount)
                    .withData("oldest_refreshed_at", oldest == null ? "never" : oldest.toString())
                    .withData("age_hours", ageHours)
                    .withData("max_allowed_hours", maxStalenessHours);

            if (rowCount == 0) {
                // Empty table = cold-start refresh pending — not stale, just not yet populated
                return b.up().build();
            }
            if (ageHours <= maxStalenessHours) {
                return b.up().build();
            }
            log.warnf("opex-distribution-freshness DOWN: rows=%d, age_hours=%d, max_hours=%d",
                    rowCount, ageHours, maxStalenessHours);
            return b.down().build();
        } catch (RuntimeException ex) {
            log.warnf(ex, "opex-distribution-freshness transient error — reporting UP defensively");
            return HealthCheckResponse
                    .named("opex-distribution-freshness")
                    .withData("transient_error", ex.getClass().getSimpleName())
                    .up().build();
        }
    }
}
