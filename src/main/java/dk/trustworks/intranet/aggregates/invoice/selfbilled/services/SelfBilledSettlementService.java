package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupPreview;
import dk.trustworks.intranet.aggregates.invoice.services.InternalInvoiceOrchestrator;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.aggregates.invoice.services.PhantomSettlementService;
import dk.trustworks.intranet.aggregates.invoice.services.SettlementDeltaCalculator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settles the self-billed clients from selfbilled_line (parallel to PhantomSettlementService,
 * left inert for these clients). target = voucher-netted self-billed per (issuer, consultant),
 * cross-company only; settled = work-period-stamped live internals; delta drives one internal /
 * credit note per issuer, threshold |delta| >= 1 kr, with the existing double-settlement guard.
 * Only work-periods with >=1 self-billed line are processed (D6b).
 */
@ApplicationScoped
public class SelfBilledSettlementService {

    private static final Logger log = Logger.getLogger(SelfBilledSettlementService.class);
    private static final BigDecimal THRESHOLD = BigDecimal.ONE; // 1 kr

    @Inject EntityManager em;
    @Inject InvoiceService invoiceService;
    @Inject InternalInvoiceOrchestrator orchestrator;
    @Inject PhantomSettlementService phantomSettlementService;   // reuse settled + duplicate-guard logic (R1)
    @Inject SelfBilledSettlementService self;

    /** (client, debtor, year, month) keys with >=1 self-billed line in the work window [fromYm,toYm]. */
    @Transactional
    public List<SettlementGroupKey> inScopeGroups(int fromYm, int toYm) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT client_uuid, debtor_company_uuid, work_year, work_month
                FROM selfbilled_line
                WHERE status = 'RESOLVED' AND work_year IS NOT NULL
                  AND (work_year*100 + work_month) BETWEEN :from AND :to
                GROUP BY client_uuid, debtor_company_uuid, work_year, work_month
                ORDER BY work_year, work_month
                """).setParameter("from", fromYm).setParameter("to", toYm).getResultList();
        List<SettlementGroupKey> keys = new ArrayList<>();
        for (Object[] r : rows) {
            keys.add(new SettlementGroupKey((String) r[0], (String) r[1],
                    ((Number) r[2]).intValue(), ((Number) r[3]).intValue()));
        }
        return keys;
    }

    /** Preview one work-period group: cross-company target vs work-period-stamped settled. Read-only. */
    @Transactional
    public SettlementGroupPreview previewGroup(SettlementGroupKey key) {
        // Cross-company target, sign-flipped to positive, straight from SQL (R2): -SUM(amount) with
        // issuer <> debtor. GROUP BY already nets per (issuer, consultant) — no separate calc class.
        @SuppressWarnings("unchecked")
        List<Object[]> tgt = em.createNativeQuery("""
                SELECT issuer_company_uuid, consultant_uuid, -COALESCE(SUM(amount),0)
                FROM selfbilled_line
                WHERE status='RESOLVED' AND client_uuid=:c AND debtor_company_uuid=:d
                  AND work_year=:y AND work_month=:m
                  AND issuer_company_uuid IS NOT NULL
                  AND issuer_company_uuid <> debtor_company_uuid
                GROUP BY issuer_company_uuid, consultant_uuid
                """).setParameter("c", key.billingClientUuid()).setParameter("d", key.debtorCompanyUuid())
                .setParameter("y", key.year()).setParameter("m", key.month()).getResultList();
        List<SettlementDeltaCalculator.TargetLine> targets = new ArrayList<>();
        for (Object[] r : tgt) {
            targets.add(new SettlementDeltaCalculator.TargetLine((String) r[0], (String) r[1],
                    toBig(r[2]).setScale(2, RoundingMode.HALF_UP)));
        }

        // Settled side reused from PhantomSettlementService (R1) — one source of truth, identical query.
        List<SettlementDeltaCalculator.SettledLine> settled = phantomSettlementService.settledLinesForGroup(key);

        Map<String, String> consultantNames = resolveConsultantNames(targets, settled);
        Map<String, String> companyNames = resolveCompanyNames(key, targets, settled);
        return SettlementDeltaCalculator.compute(key, key.debtorCompanyUuid(), targets, settled,
                consultantNames, companyNames, true);
    }

    /** Settle one group: one document per issuer whose |delta| >= 1 kr, skipping issuers with an open QUEUED doc. */
    public List<String> settleGroup(SettlementGroupKey key, boolean queue) {
        SettlementGroupPreview preview = self.previewGroup(key);
        String representative = representativePhantom(key.billingClientUuid());
        if (representative == null) { log.warnf("settleGroup: no representative phantom for client=%s", key.billingClientUuid()); return List.of(); }

        List<String> created = new ArrayList<>();
        for (SettlementGroupPreview.IssuerDelta issuer : preview.issuers()) {
            if (issuer.delta().abs().compareTo(THRESHOLD) < 0) continue;   // threshold: skip øre-rounding
            if (phantomSettlementService.hasOpenQueuedInternal(key, issuer.issuerCompanyUuid())) {   // reused guard (R1/#2)
                log.warnf("settleGroup: open QUEUED internal for group=%s issuer=%s; skipping", key.asString(), issuer.issuerCompanyUuid());
                continue;
            }
            List<InvoiceService.SettlementLineInput> lines = new ArrayList<>();
            for (SettlementGroupPreview.ConsultantDelta c : issuer.consultants()) {
                if (c.delta().abs().compareTo(THRESHOLD) < 0) continue;
                lines.add(new InvoiceService.SettlementLineInput(c.consultantUuid(), c.consultantName(), c.delta()));
            }
            if (lines.isEmpty()) continue;
            try {
                String internalUuid = invoiceService.createSettlementInternal(
                        representative, issuer.issuerCompanyUuid(), key.debtorCompanyUuid(), lines,
                        key.billingClientUuid(), key.year(), key.month());
                created.add(internalUuid);
                if (!queue) orchestrator.finalizeAutomatically(internalUuid);
            } catch (RuntimeException e) {
                log.errorf(e, "settleGroup: issuer=%s group=%s failed; skipping", issuer.issuerCompanyUuid(), key.asString());
            }
        }
        log.infof("settleGroup group=%s created=%d queue=%s", key.asString(), created.size(), queue);
        return created;
    }

    String representativePhantom(String billingClientUuid) {
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery("""
                SELECT uuid FROM invoices
                WHERE type='PHANTOM' AND status='CREATED' AND economics_entry_number IS NOT NULL
                  AND billing_client_uuid=:c ORDER BY uuid LIMIT 1
                """).setParameter("c", billingClientUuid).getResultList();
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static BigDecimal toBig(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return (o instanceof BigDecimal b) ? b : BigDecimal.valueOf(((Number) o).doubleValue());
    }

    private Map<String, String> resolveConsultantNames(List<SettlementDeltaCalculator.TargetLine> t,
                                                       List<SettlementDeltaCalculator.SettledLine> s) {
        Set<String> ids = new HashSet<>();
        for (var x : t) ids.add(x.consultantUuid());
        for (var x : s) ids.add(x.consultantUuid());
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT uuid, CONCAT(firstname,' ',lastname) FROM user WHERE uuid IN (:ids)")
                .setParameter("ids", ids).getResultList();
        for (Object[] r : rows) out.put((String) r[0], (String) r[1]);
        return out;
    }

    private Map<String, String> resolveCompanyNames(SettlementGroupKey key,
                                                    List<SettlementDeltaCalculator.TargetLine> t,
                                                    List<SettlementDeltaCalculator.SettledLine> s) {
        Set<String> ids = new HashSet<>();
        ids.add(key.debtorCompanyUuid());
        for (var x : t) ids.add(x.issuerCompanyUuid());
        for (var x : s) ids.add(x.issuerCompanyUuid());
        Map<String, String> out = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT uuid, name FROM companies WHERE uuid IN (:ids)")
                .setParameter("ids", ids).getResultList();
        for (Object[] r : rows) out.put((String) r[0], (String) r[1]);
        return out;
    }
}
