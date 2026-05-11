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
        Instant start = Instant.now();
        LocalDate today = LocalDate.now();

        FiscalYearRange currentFy =
                UtilizationCalculationHelper.getCurrentFiscalYearRange();
        LocalDate windowFrom = currentFy.start().minusYears(fyBack);
        LocalDate windowTo = currentFy.end().plusDays(1);  // exclusive

        // 1. Load all source data for the window in ONE batch (existing helper).
        FiscalYearData fyData = intercompanyCalcService.loadFiscalYear(
                windowFrom, windowTo, salaryBufferMultiplier);

        // 2. For each month, compute distribution rows. The provider's
        //    computeDistributionForMonth is @CacheResult-annotated per monthKey,
        //    but since the cache key matches the per-month workload and we
        //    explicitly DELETE before INSERT, cached output is the right thing.
        List<OpexRow> allRows = new ArrayList<>();
        for (YearMonth ym : fyData.perMonth.keySet()) {
            allRows.addAll(opexProvider.computeDistributionForMonth(
                    ym,
                    fyData.perMonth.get(ym),
                    fyData.lumpsByMonth.getOrDefault(ym, Collections.emptyMap())));
        }

        // 3. DELETE + INSERT for the window (idempotent, single tx).
        String fromKey = UtilizationCalculationHelper.toMonthKey(windowFrom);
        String toKey = UtilizationCalculationHelper.toMonthKey(windowTo);

        int deleted = em.createNativeQuery(
                "DELETE FROM fact_opex_distribution_mat " +
                "WHERE month_key >= :fromKey AND month_key < :toKey")
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
                .executeUpdate();

        int inserted = bulkInsert(allRows, LocalDateTime.now());

        Duration took = Duration.between(start, Instant.now());
        log.infof("Refreshed fact_opex_distribution_mat: deleted=%d inserted=%d took=%dms window=[%s..%s)",
                deleted, inserted, took.toMillis(), windowFrom, windowTo);

        return new RefreshOutcome(inserted, deleted, took, windowFrom, windowTo);
    }

    private int bulkInsert(List<OpexRow> rows, LocalDateTime refreshedAt) {
        if (rows.isEmpty()) return 0;

        StringBuilder sql = new StringBuilder(
                "INSERT INTO fact_opex_distribution_mat " +
                "(opex_distribution_id, company_id, cost_center_id, expense_category_id, " +
                " month_key, year, month_number, fiscal_year, fiscal_month_number, " +
                " fiscal_month_key, cost_type, opex_amount_dkk, is_payroll_flag, " +
                " invoice_count, data_source, refreshed_at) VALUES ");

        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("(:id").append(i).append(", :company").append(i)
               .append(", :cc").append(i).append(", :cat").append(i)
               .append(", :mk").append(i).append(", :yr").append(i)
               .append(", :mn").append(i).append(", :fy").append(i)
               .append(", :fmn").append(i).append(", :fmk").append(i)
               .append(", :ct").append(i).append(", :amt").append(i)
               .append(", :pf").append(i).append(", :ic").append(i)
               .append(", :ds").append(i).append(", :ra").append(i).append(")");
        }
        // Idempotency safety: surrogate key collisions are impossible inside one
        // refresh (we DELETE the window first), but ON DUPLICATE KEY UPDATE
        // protects against accidental concurrent runs.
        sql.append(" ON DUPLICATE KEY UPDATE " +
                "  opex_amount_dkk = VALUES(opex_amount_dkk), " +
                "  invoice_count   = VALUES(invoice_count), " +
                "  refreshed_at    = VALUES(refreshed_at)");

        Query q = em.createNativeQuery(sql.toString());
        for (int i = 0; i < rows.size(); i++) {
            OpexRow r = rows.get(i);
            YearMonth ym = YearMonth.parse(r.monthKey(),
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            int monthVal = ym.getMonthValue();
            int yearVal = ym.getYear();
            int fyVal = monthVal >= 7 ? yearVal : yearVal - 1;
            int fmn = monthVal >= 7 ? monthVal - 6 : monthVal + 6;
            String fmk = String.format("FY%04d-%02d", fyVal, fmn);
            String cost = r.isPayrollFlag() ? "SALARIES" : "OPEX";
            String id = r.companyId() + "-" + r.costCenterId() + "-"
                      + r.expenseCategoryId() + "-" + (r.isPayrollFlag() ? "1" : "0")
                      + "-" + r.monthKey();

            q.setParameter("id" + i, id)
             .setParameter("company" + i, r.companyId())
             .setParameter("cc" + i, r.costCenterId())
             .setParameter("cat" + i, r.expenseCategoryId())
             .setParameter("mk" + i, r.monthKey())
             .setParameter("yr" + i, yearVal)
             .setParameter("mn" + i, monthVal)
             .setParameter("fy" + i, fyVal)
             .setParameter("fmn" + i, fmn)
             .setParameter("fmk" + i, fmk)
             .setParameter("ct" + i, cost)
             .setParameter("amt" + i, r.opexAmountDkk())
             .setParameter("pf" + i, r.isPayrollFlag() ? 1 : 0)
             .setParameter("ic" + i, r.invoiceCount())
             .setParameter("ds" + i, OpexRow.SOURCE_DISTRIBUTION)
             .setParameter("ra" + i, refreshedAt);
        }
        return q.executeUpdate();
    }
}
