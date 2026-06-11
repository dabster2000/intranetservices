package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.services.SettlementQueryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared settlement math for the workbench. target comes from the HUMAN assignments
 * (never timesheets — AC1); settled reuses SettlementQueryService.settledLinesForGroup
 * (the one source of truth for the settled side, spec §8). All returns are normalized
 * positive ("owed to the issuer").
 */
@ApplicationScoped
public class SelfBilledDeltaQuery {

    @Inject EntityManager em;
    @Inject SettlementQueryService settlementQueryService;

    /** The debtor (agreement) company configured for an in-scope client, or null. */
    public String debtorFor(String clientUuid) {
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery(
                        "SELECT agreement_company_uuid FROM selfbilled_source WHERE client_uuid = :c AND enabled = 1 LIMIT 1")
                .setParameter("c", clientUuid).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Normalized positive target: -Σ signed share for (client, consultant, work period). */
    public BigDecimal target(String clientUuid, String consultantUuid, int workYear, int workMonth) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                SELECT COALESCE(-SUM(a.share_amount), 0)
                FROM selfbilled_assignment a
                JOIN selfbilled_line l ON l.uuid = a.selfbilled_line_uuid
                WHERE l.client_uuid = :c AND a.consultant_uuid = :u
                  AND a.work_year = :y AND a.work_month = :m
                """).setParameter("c", clientUuid).setParameter("u", consultantUuid)
                .setParameter("y", workYear).setParameter("m", workMonth)
                .getResultList();
        Object v = rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
        return toBig(v).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Σ settled (signed hours*rate) for ONE consultant within the group's stamped live internals.
     *
     * NOTE: each call issues one DB query via SettlementQueryService#settledLinesForGroup.
     * Callers iterating multiple consultants for the same key should call settledLinesForGroup
     * once and filter in Java.
     */
    public BigDecimal settled(SettlementGroupKey key, String consultantUuid) {
        return settlementQueryService.settledLinesForGroup(key).stream()
                .filter(s -> consultantUuid.equals(s.consultantUuid()))
                .map(SettlementQueryService.SettledLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** delta = target - settled for one (client, consultant, work-period). */
    public BigDecimal delta(String clientUuid, String debtorCompanyUuid, String consultantUuid,
                            int workYear, int workMonth) {
        SettlementGroupKey key = new SettlementGroupKey(clientUuid, debtorCompanyUuid, workYear, workMonth);
        return target(clientUuid, consultantUuid, workYear, workMonth)
                .subtract(settled(key, consultantUuid));
    }

    /**
     * 8610 'Samlekonto debitorer' remainder per self-billing voucher backing one
     * (client, consultant, work-period) — the shared lookup feeding {@link SelfBilledPaidGate#allPaid}
     * for BOTH the workbench read ({@code /internals/queued}) and the nightly auto-finalize batchlet.
     *
     * <p>The self-billing vouchers are the distinct {@code selfbilled_line.voucher_number}s carrying a
     * HUMAN/AUTO assignment for the group. Their debtor leg is booked on account 8610 at the agreement
     * (debtor) company; the e-conomic {@code remainder} on that 8610 row is the still-unpaid balance, so
     * remainder == 0 means the client has paid the self-billing invoice. A voucher with NO 8610
     * finance_details row yields a {@code null} remainder (fail closed — never auto-finalize on missing
     * evidence). Returns one {@link SelfBilledPaidGate.VoucherRemainder} per backing voucher (empty when
     * the group has no assigned vouchers).
     *
     * <p>FISCAL-YEAR ANCHOR (I-1): voucher numbers are NOT globally unique — e-conomic resets them per
     * accounting year (the codebase's own uniqueness domain is {@code (vouchernumber, journalnumber,
     * accountingyear)}, see Expense / ExpenseOrphanDetectionBatchlet), and FinanceLoadJob loads several
     * fiscal years concurrently, so a same-numbered 8610 row from a DIFFERENT year is co-resident. Without
     * a year constraint a collision could fail OPEN (real row missing + foreign-year row with remainder 0
     * → allPaid → auto-finalize an UNPAID group). We therefore constrain the 8610 row's {@code expensedate}
     * to the SAME fiscal year (Jul 1 → Jun 30, the project-wide convention) as the voucher's
     * {@code selfbilled_line.booking_date} — the exact uniqueness domain (a fiscal-year WINDOW, not exact-
     * date equality, so correction entries booked on other days within the year still match).
     */
    public List<SelfBilledPaidGate.VoucherRemainder> voucherRemainders(
            String clientUuid, String debtorCompanyUuid, String consultantUuid, int workYear, int workMonth) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT v.voucher_number, fd.remainder
                FROM (
                    SELECT l.voucher_number, MIN(l.booking_date) AS booking_date
                    FROM selfbilled_assignment a
                    JOIN selfbilled_line l ON l.uuid = a.selfbilled_line_uuid
                    WHERE l.client_uuid = :c AND a.consultant_uuid = :u
                      AND a.work_year = :y AND a.work_month = :m
                    GROUP BY l.voucher_number
                ) v
                LEFT JOIN finance_details fd
                       ON fd.vouchernumber = v.voucher_number
                      AND fd.companyuuid = :debtor
                      AND fd.accountnumber = 8610
                      AND fd.expensedate BETWEEN
                            DATE(CONCAT(YEAR(v.booking_date) - (MONTH(v.booking_date) < 7), '-07-01'))
                        AND DATE(CONCAT(YEAR(v.booking_date) + (MONTH(v.booking_date) >= 7), '-06-30'))
                """).setParameter("c", clientUuid).setParameter("u", consultantUuid)
                .setParameter("y", workYear).setParameter("m", workMonth)
                .setParameter("debtor", debtorCompanyUuid)
                .getResultList();
        List<SelfBilledPaidGate.VoucherRemainder> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            int voucher = ((Number) r[0]).intValue();
            BigDecimal remainder = r[1] == null ? null : toBig(r[1]);
            out.add(new SelfBilledPaidGate.VoucherRemainder(voucher, remainder));
        }
        return out;
    }

    private static BigDecimal toBig(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }
}
