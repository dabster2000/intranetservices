package dk.trustworks.intranet.aggregates.finance.health;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Readiness probe that verifies the dedicated {@code health} datasource pool can
 * acquire a connection and execute a trivial {@code SELECT 1}.
 *
 * <p>The {@code health} pool is sized at min=1/max=4 and exists specifically so
 * that when the default pool starves under load, this probe still succeeds and
 * the ALB does not kill the task. When the database itself is unreachable —
 * the only scenario in which both pools would fail — readiness will fail
 * legitimately and the ALB will stop sending traffic without invoking
 * liveness-driven container kills.
 *
 * <p>Uses {@code @Readiness} (not {@code @Liveness}) so that a transient DB
 * outage causes the task to be removed from the load balancer but not killed
 * and restarted, preventing the historic kill-restart cascade documented in
 * Wave 1A pool-starvation remediation (2026-05-27).
 */
@JBossLog
@Readiness
@ApplicationScoped
public class HealthDatasourceCheck implements HealthCheck {

    @Inject
    @DataSource("health")
    AgroalDataSource healthDs;

    @Override
    public HealthCheckResponse call() {
        try (Connection c = healthDs.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1")) {
            rs.next();
            return HealthCheckResponse.up("health-pool-probe");
        } catch (SQLException ex) {
            log.warnf(ex, "health datasource probe failed (SQL: sqlState=%s, errorCode=%d)",
                    ex.getSQLState(), ex.getErrorCode());
            return HealthCheckResponse.down("health-pool-probe");
        } catch (RuntimeException ex) {
            // Defensive net: CDI resolution problems, Agroal wrappers that surface as
            // unchecked exceptions, or driver-level RuntimeExceptions must not leak
            // out of call() — SmallRye would still mark DOWN, but without log line or
            // data payload, defeating the observability purpose of this probe.
            log.warnf(ex, "health datasource probe failed (runtime)");
            return HealthCheckResponse.down("health-pool-probe");
        }
    }
}
