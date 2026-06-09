package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.RestampDecision;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.RestampReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Orchestrates the gated 3-phase migration: capture -> re-stamp (report-first) -> settle. */
@ApplicationScoped
public class SelfBilledMigrationService {

    private static final Logger log = Logger.getLogger(SelfBilledMigrationService.class);

    @Inject EntityManager em;
    @Inject SelfBilledImportService importService;
    @Inject SelfBilledSettlementService settlementService;

    /** Phase 1. */
    public Map<String, Integer> phase1Capture(LocalDate from, LocalDate to) {
        return importService.capture(from, to);
    }

    /**
     * Phase 2 (report-first). Build internals + targets, plan, and when apply=true write
     * settlement_* for RESTAMP outcomes using the decision's own client/debtor/period.
     */
    @Transactional
    public RestampReport phase2Restamp(int fromYm, int toYm, boolean apply) {
        List<SelfBilledRestampPlanner.Internal> internals = loadCrossCompanyInternals();
        List<SelfBilledRestampPlanner.Target> targets = loadTargets(fromYm, toYm);
        List<RestampDecision> decisions = SelfBilledRestampPlanner.plan(internals, targets);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RestampDecision d : decisions) counts.merge(d.outcome().name(), 1, Integer::sum);

        if (apply) {
            // Safety: a multi-consultant internal yields multiple RESTAMP decisions for the same
            // invoice uuid; if they disagree on the target period, the per-row UPDATEs would silently
            // last-write-win. Refuse to apply on such a conflict — the @Transactional rolls back so
            // nothing is written, and report mode (apply=false) still shows the decisions for review.
            Map<String, Set<String>> stampsByInternal = new HashMap<>();
            for (RestampDecision d : decisions) {
                if (d.outcome() != RestampDecision.Outcome.RESTAMP) continue;
                stampsByInternal.computeIfAbsent(d.internalUuid(), k -> new HashSet<>())
                        .add(d.workYear() + "-" + d.workMonth());
            }
            for (Map.Entry<String, Set<String>> e : stampsByInternal.entrySet()) {
                if (e.getValue().size() > 1) {
                    throw new IllegalStateException("Conflicting RESTAMP target periods for internal "
                            + e.getKey() + ": " + e.getValue() + " — aborting apply");
                }
            }
            for (RestampDecision d : decisions) {
                if (d.outcome() != RestampDecision.Outcome.RESTAMP) continue;
                em.createNativeQuery("""
                        UPDATE invoices SET settlement_billing_client_uuid=:c, settlement_debtor_companyuuid=:d,
                               settlement_year=:y, settlement_month=:m WHERE uuid=:u
                        """).setParameter("c", d.clientUuid()).setParameter("d", d.debtorCompanyUuid())
                        .setParameter("y", d.workYear()).setParameter("m", d.workMonth())
                        .setParameter("u", d.internalUuid()).executeUpdate();
            }
        }
        log.infof("phase2Restamp apply=%s counts=%s", apply, counts);
        return new RestampReport(apply, counts, decisions);
    }

    /** Phase 3: settle every in-scope work-period group. */
    public List<String> phase3Settle(int fromYm, int toYm, boolean queue) {
        List<String> created = new ArrayList<>();
        for (SettlementGroupKey key : settlementService.inScopeGroups(fromYm, toYm)) {
            created.addAll(settlementService.settleGroup(key, queue));
        }
        log.infof("phase3Settle created=%d", created.size());
        return created;
    }

    /**
     * In-scope existing internals: cross-company INTERNAL/INTERNAL_SERVICE whose phantom ref's
     * billing client is a configured self-billed source. client/debtor come from the phantom ref +
     * the internal's own debtor column — deterministic, no consultant inference (review fix #5).
     * Internals without a resolvable self-billed phantom ref are simply not in scope.
     */
    private List<SelfBilledRestampPlanner.Internal> loadCrossCompanyInternals() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT i.uuid, p.billing_client_uuid, i.debtor_companyuuid, ii.consultantuuid,
                       COALESCE(SUM(ii.hours*ii.rate),0), i.settlement_year, i.settlement_month
                FROM invoices i
                JOIN invoices p ON p.uuid = i.invoice_ref_uuid AND p.type = 'PHANTOM'
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.type IN ('INTERNAL','INTERNAL_SERVICE')
                  AND i.status IN ('PENDING_REVIEW','QUEUED','CREATED')
                  AND i.companyuuid <> i.debtor_companyuuid
                  AND p.billing_client_uuid IN (SELECT client_uuid FROM selfbilled_source WHERE enabled = 1)
                GROUP BY i.uuid, p.billing_client_uuid, i.debtor_companyuuid, ii.consultantuuid,
                         i.settlement_year, i.settlement_month
                """).getResultList();
        List<SelfBilledRestampPlanner.Internal> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new SelfBilledRestampPlanner.Internal((String) r[0], (String) r[1], (String) r[2], (String) r[3],
                    toBig(r[4]), r[5] == null ? null : ((Number) r[5]).intValue(),
                    r[6] == null ? null : ((Number) r[6]).intValue()));
        }
        return out;
    }

    private List<SelfBilledRestampPlanner.Target> loadTargets(int fromYm, int toYm) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT client_uuid, debtor_company_uuid, consultant_uuid, work_year, work_month,
                       COALESCE(SUM(amount),0)
                FROM selfbilled_line
                WHERE status='RESOLVED' AND issuer_company_uuid <> debtor_company_uuid
                  AND (work_year*100 + work_month) BETWEEN :from AND :to
                GROUP BY client_uuid, debtor_company_uuid, consultant_uuid, work_year, work_month
                """).setParameter("from", fromYm).setParameter("to", toYm).getResultList();
        List<SelfBilledRestampPlanner.Target> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new SelfBilledRestampPlanner.Target((String) r[0], (String) r[1], (String) r[2],
                    ((Number) r[3]).intValue(), ((Number) r[4]).intValue(),
                    toBig(r[5]).negate())); // negate signed revenue -> positive
        }
        return out;
    }

    private static BigDecimal toBig(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return (o instanceof BigDecimal b) ? b : BigDecimal.valueOf(((Number) o).doubleValue());
    }
}
