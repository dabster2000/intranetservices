package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService.FiscalYearData;
import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.FiscalYearRange;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Refreshes {@code fact_opex_distribution_mat} by running the existing
 * {@link IntercompanyCalcService#loadFiscalYear} + distribution algorithm
 * once per night and writing the resulting {@link OpexRow}s into the table.
 *
 * <p>The provider that powers the CXO EBITDA forecast endpoint reads from this
 * table for unsettled months instead of recomputing on the request path.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-11-fact-opex-distribution-mat-design.md
 */
@ApplicationScoped
@JBossLog
public class OpexDistributionRefreshService {

    @Inject
    IntercompanyCalcService intercompanyCalcService;

    @Inject
    DistributionAwareOpexProvider opexProvider;

    @Inject
    EntityManager em;

    @ConfigProperty(name = "dk.trustworks.intranet.opex-distribution.refresh-window-fy-back", defaultValue = "1")
    int fyBack;

    @ConfigProperty(name = "dk.trustworks.intranet.aggregates.accounting.salary-buffer-multiplier", defaultValue = "1.02")
    double salaryBufferMultiplier;

    public record RefreshOutcome(int inserted, int deleted, Duration took,
                                 LocalDate windowFrom, LocalDate windowTo) {}

    /**
     * Rebuild all rows in the window [currentFY - fyBack, currentFY + 1).
     * Idempotent — safe to call any number of times.
     */
    @Transactional
    public RefreshOutcome refresh() {
        // Filled in in Task 3.
        return new RefreshOutcome(0, 0, Duration.ZERO,
                LocalDate.now(), LocalDate.now());
    }
}
