package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.services.PhantomSettlementService;
import dk.trustworks.intranet.aggregates.invoice.services.SettlementDeltaCalculator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Shared settlement math for the workbench. target comes from the HUMAN assignments
 * (never timesheets — AC1); settled reuses PhantomSettlementService.settledLinesForGroup
 * (the one source of truth for the settled side, spec §8). All returns are normalized
 * positive ("owed to the issuer").
 */
@ApplicationScoped
public class SelfBilledDeltaQuery {

    @Inject EntityManager em;
    @Inject PhantomSettlementService phantomSettlementService;

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
     * NOTE: each call issues one DB query via PhantomSettlementService#settledLinesForGroup.
     * Callers iterating multiple consultants for the same key should call settledLinesForGroup
     * once and filter in Java.
     */
    public BigDecimal settled(SettlementGroupKey key, String consultantUuid) {
        return phantomSettlementService.settledLinesForGroup(key).stream()
                .filter(s -> consultantUuid.equals(s.consultantUuid()))
                .map(SettlementDeltaCalculator.SettledLine::amount)
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

    private static BigDecimal toBig(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }
}
