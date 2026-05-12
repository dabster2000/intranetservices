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
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT MIN(refreshed_at), COUNT(*) FROM fact_opex_distribution_mat")
                .getSingleResult();
        LocalDateTime oldest = row[0] == null ? null
                : ((Timestamp) row[0]).toLocalDateTime();
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
    }
}
