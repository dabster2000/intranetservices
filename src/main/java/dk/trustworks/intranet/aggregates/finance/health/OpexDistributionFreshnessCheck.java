package dk.trustworks.intranet.aggregates.finance.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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
    EntityManagerFactory emf;

    @ConfigProperty(name = "dk.trustworks.intranet.opex-distribution.max-staleness-hours", defaultValue = "30")
    int maxStalenessHours;

    @Override
    public HealthCheckResponse call() {
        // SmallRye health runs @Readiness checks concurrently on multiple
        // threads, so we open a fresh EntityManager per call rather than
        // sharing the @Inject EntityManager (whose request-scope doesn't
        // isolate per-thread when the caller isn't an HTTP request). This
        // removes the root cause of the ConcurrentModificationException
        // observed on staging 2026-05-13/2026-05-15 (CME from Hibernate
        // internals on concurrent shared-EM access, which previously caused
        // a canary health-probe failure and ECS Express deprovisioning).
        // The defensive try/catch remains as a safety net for unexpected
        // runtime errors — a false DOWN would suppress legitimate freshness
        // alerting, and both production tasks share the same DB so readiness
        // does not actually shed load.
        try (EntityManager localEm = emf.createEntityManager()) {
            Object[] row = (Object[]) localEm.createNativeQuery(
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
