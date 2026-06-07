package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupPreview;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupRow;
import dk.trustworks.intranet.aggregates.invoice.model.InternalInvoicePhantomLink;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consolidated, delta-based settlement of cross-company PHANTOM labour.
 * Built up across phases: Phase 2 — group-key derivation only (inert);
 * Phase 3 — backfill; Phase 4 — listSettlementGroups + previewGroup (read);
 * Phase 5 — settleGroup (write). See the master plan's Shared contracts.
 */
@ApplicationScoped
public class PhantomSettlementService {

    private static final Logger log = Logger.getLogger(PhantomSettlementService.class);

    /**
     * Settlement-group key for a phantom: (billing client, receiving/debtor company,
     * year, month). Returns null when the phantom is unmapped (no billing client) —
     * such phantoms cannot form a group and are handled by the review queue.
     */
    public SettlementGroupKey groupKeyOf(Invoice phantom) {
        if (phantom == null) return null;
        String companyUuid = (phantom.getCompany() != null) ? phantom.getCompany().getUuid() : null;
        return SettlementGroupKey.from(phantom.getBillingClientUuid(), companyUuid,
                phantom.getYear(), phantom.getMonth());
    }

    @Inject
    EntityManager em;

    /**
     * Self-injected proxy so the per-internal REQUIRES_NEW boundary actually engages
     * (a plain this.method() self-call bypasses the Arc interceptor). One failed
     * internal cannot roll back the whole backfill. Mirrors PhantomAttributionService.self.
     */
    @Inject
    PhantomSettlementService self;

    // Phase 4 (preview) collaborators — same beans InvoiceService.previewInternal uses.
    @Inject
    InvoiceAttributionService invoiceAttributionService;

    @Inject
    PhantomAttributionService phantomAttributionService;

    @Inject
    UserCompanyResolver userCompanyResolver;

    /** Outcome of backfilling one internal — keys of the returned counts map. */
    public enum BackfillOutcome { STAMPED, ALREADY_DONE, SKIPPED_UNMAPPED, SKIPPED_NO_PHANTOM, SKIPPED_NO_INTERNAL, ERROR }

    /**
     * One-time, idempotent backfill (Decision D5). Stamps the settlement-group key and
     * writes an internal_invoice_phantom_link row onto every already-issued
     * INTERNAL/INTERNAL_SERVICE invoice whose invoice_ref_uuid points to an in-scope phantom.
     * Human-invoked (POST .../settlement/backfill); never auto-runs. NOT @Transactional:
     * each item commits in its own REQUIRES_NEW tx via self, so partial progress is durable
     * and there is no outer snapshot (the REPEATABLE-READ trap is structurally absent).
     */
    public Map<String, Integer> backfillExistingInternals() {
        List<String> internalUuids = self.listBackfillCandidateInternalUuids();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String uuid : internalUuids) {
            BackfillOutcome outcome;
            try {
                outcome = self.backfillOneInternal(uuid);
            } catch (RuntimeException e) {
                log.errorf(e, "backfillOneInternal failed for internal=%s", uuid);
                outcome = BackfillOutcome.ERROR;
            }
            counts.merge(outcome.name(), 1, Integer::sum);
        }
        log.infof("backfillExistingInternals: processed=%d result=%s", internalUuids.size(), counts);
        return counts;
    }

    /** Candidate internals: INTERNAL/INTERNAL_SERVICE whose ref is an in-scope phantom. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<String> listBackfillCandidateInternalUuids() {
        String sql = """
            SELECT i.uuid
            FROM invoices i
            JOIN invoices p ON p.uuid = i.invoice_ref_uuid
            WHERE i.type IN ('INTERNAL','INTERNAL_SERVICE')
              AND i.status IN ('PENDING_REVIEW','QUEUED','CREATED')
              AND i.invoice_ref_uuid IS NOT NULL
              AND p.type = 'PHANTOM'
              AND p.economics_entry_number IS NOT NULL
            ORDER BY i.uuid
        """;
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery(sql).getResultList();
        return ids;
    }

    /**
     * Stamp one internal + write its phantom-link row. Idempotent: re-running is
     * ALREADY_DONE once both the stamp and the link exist. attributed_amount_at_issue
     * is the internal's own signed total (its lines came from that phantom).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public BackfillOutcome backfillOneInternal(String internalUuid) {
        Invoice internal = Invoice.findById(internalUuid);
        if (internal == null) return BackfillOutcome.SKIPPED_NO_INTERNAL;

        String refUuid = internal.getInvoiceRefUuid();
        if (refUuid == null || refUuid.isBlank()) return BackfillOutcome.SKIPPED_NO_PHANTOM;
        Invoice phantom = Invoice.findById(refUuid);
        if (phantom == null || phantom.getType() != InvoiceType.PHANTOM) return BackfillOutcome.SKIPPED_NO_PHANTOM;

        SettlementGroupKey key = groupKeyOf(phantom);
        if (key == null) return BackfillOutcome.SKIPPED_UNMAPPED;

        boolean alreadyStamped = internal.getSettlementYear() != null;
        boolean linkExists = InternalInvoicePhantomLink
                .count("internalUuid = ?1 and phantomUuid = ?2", internalUuid, phantom.getUuid()) > 0;
        if (alreadyStamped && linkExists) return BackfillOutcome.ALREADY_DONE;

        if (!alreadyStamped) {
            internal.setSettlementBillingClientUuid(key.billingClientUuid());
            internal.setSettlementDebtorCompanyuuid(key.debtorCompanyUuid());
            internal.setSettlementYear(key.year());
            internal.setSettlementMonth(key.month());
        }
        if (!linkExists) {
            new InternalInvoicePhantomLink(internalUuid, phantom.getUuid(), internalTotalSigned(internalUuid)).persist();
        }
        return BackfillOutcome.STAMPED;
    }

    /** Signed Σ(hours*rate) over an invoice's items (negative for a credit note). */
    BigDecimal internalTotalSigned(String invoiceUuid) {
        Object result = em.createNativeQuery(
                "SELECT COALESCE(SUM(hours*rate),0) FROM invoiceitems WHERE invoiceuuid = :id")
                .setParameter("id", invoiceUuid)
                .getSingleResult();
        BigDecimal v = (result == null) ? BigDecimal.ZERO
                : (result instanceof BigDecimal b ? b : BigDecimal.valueOf(((Number) result).doubleValue()));
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * One row per settlement group in the window. target = Σ attributed_amount over the
     * group's phantom attributions (signed, Decision D1). settled = Σ signed (hours*rate) over
     * live internals stamped with the group key (Phase-2 columns; historical internals require
     * the Phase-3 backfill to be counted). Read-only.
     *
     * <p>NOTE — this group target is intentionally COARSER than {@link #previewGroup}'s total:
     * it is the full D1 figure (every phantom attribution, including consultants whose own
     * company is the debtor company, and consultants whose company cannot be resolved as-of the
     * period). previewGroup's totalTarget is the cross-company-only settleable amount (it drops
     * same-company and unmapped consultants, because a company never transfer-prices to itself).
     * The two therefore do NOT reconcile line-for-line for groups that contain same-company or
     * unmapped labour — the FE must not present {@code SettlementGroupRow.outstanding} and
     * {@code SettlementGroupPreview.totalDelta} as the same number. The Settle dialog total is
     * the authoritative settleable amount.
     */
    @Transactional
    public List<SettlementGroupRow> listSettlementGroups(LocalDate fromdate, LocalDate todate) {
        LocalDate from = (fromdate != null) ? fromdate : LocalDate.of(2014, 1, 1);
        LocalDate to   = (todate   != null) ? todate   : LocalDate.now();

        String sql = """
            WITH grp AS (
                SELECT billing_client_uuid AS client_uuid, companyuuid, year, month,
                       MAX(clientname) AS client_name, COUNT(*) AS phantom_count
                FROM invoices
                WHERE type='PHANTOM' AND status='CREATED' AND economics_entry_number IS NOT NULL
                  AND internal_invoice_skip = 0
                  AND billing_client_uuid IS NOT NULL
                  AND invoicedate >= :from AND invoicedate < :to
                GROUP BY billing_client_uuid, companyuuid, year, month
            ),
            tgt AS (
                SELECT p.billing_client_uuid AS client_uuid, p.companyuuid, p.year, p.month,
                       COALESCE(SUM(iia.attributed_amount),0) AS target
                FROM invoices p
                JOIN invoiceitems ii ON ii.invoiceuuid = p.uuid
                JOIN invoice_item_attributions iia ON iia.invoiceitem_uuid = ii.uuid
                WHERE p.type='PHANTOM' AND p.status='CREATED' AND p.economics_entry_number IS NOT NULL
                  AND p.internal_invoice_skip = 0
                  AND p.billing_client_uuid IS NOT NULL
                  AND p.invoicedate >= :from AND p.invoicedate < :to
                GROUP BY p.billing_client_uuid, p.companyuuid, p.year, p.month
            ),
            cn AS (
                SELECT client_uuid, companyuuid, year, month, MAX(is_neg) AS has_cn
                FROM (
                    SELECT p.billing_client_uuid AS client_uuid, p.companyuuid, p.year, p.month,
                           CASE WHEN COALESCE(SUM(ii.hours*ii.rate),0) < 0 THEN 1 ELSE 0 END AS is_neg
                    FROM invoices p JOIN invoiceitems ii ON ii.invoiceuuid = p.uuid
                    WHERE p.type='PHANTOM' AND p.status='CREATED' AND p.economics_entry_number IS NOT NULL
                      AND p.internal_invoice_skip = 0
                      AND p.billing_client_uuid IS NOT NULL
                      AND p.invoicedate >= :from AND p.invoicedate < :to
                    GROUP BY p.uuid, p.billing_client_uuid, p.companyuuid, p.year, p.month
                ) per_phantom
                GROUP BY client_uuid, companyuuid, year, month
            ),
            setl AS (
                SELECT s.settlement_billing_client_uuid AS client_uuid, s.settlement_debtor_companyuuid AS companyuuid,
                       s.settlement_year AS year, s.settlement_month AS month,
                       COALESCE(SUM(sii.hours*sii.rate),0) AS settled,
                       COUNT(DISTINCT s.uuid) AS internal_count
                FROM invoices s
                JOIN invoiceitems sii ON sii.invoiceuuid = s.uuid
                WHERE s.type IN ('INTERNAL','INTERNAL_SERVICE')
                  AND s.status IN ('PENDING_REVIEW','QUEUED','CREATED')
                  AND s.settlement_year IS NOT NULL
                GROUP BY s.settlement_billing_client_uuid, s.settlement_debtor_companyuuid, s.settlement_year, s.settlement_month
            )
            SELECT grp.client_uuid, grp.companyuuid, grp.year, grp.month, grp.client_name, grp.phantom_count,
                   COALESCE(tgt.target,0)        AS target,
                   COALESCE(setl.settled,0)      AS settled,
                   COALESCE(setl.internal_count,0) AS internal_count,
                   COALESCE(cn.has_cn,0)         AS has_cn
            FROM grp
            LEFT JOIN tgt  ON tgt.client_uuid=grp.client_uuid AND tgt.companyuuid=grp.companyuuid AND tgt.year=grp.year AND tgt.month=grp.month
            LEFT JOIN setl ON setl.client_uuid=grp.client_uuid AND setl.companyuuid=grp.companyuuid AND setl.year=grp.year AND setl.month=grp.month
            LEFT JOIN cn   ON cn.client_uuid=grp.client_uuid AND cn.companyuuid=grp.companyuuid AND cn.year=grp.year AND cn.month=grp.month
            ORDER BY grp.year DESC, grp.month DESC, grp.client_name
        """;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("from", from).setParameter("to", to).getResultList();
        if (rows.isEmpty()) return List.of();

        // Resolve debtor-company names in bulk (avoid guessing the company table name in SQL).
        Set<String> companyUuids = new HashSet<>();
        for (Object[] r : rows) companyUuids.add((String) r[1]);
        Map<String, String> companyNames = resolveCompanyNames(companyUuids);

        List<SettlementGroupRow> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String clientUuid = (String) r[0];
            String companyUuid = (String) r[1];
            int year  = ((Number) r[2]).intValue();
            int month = ((Number) r[3]).intValue();
            String clientName = (String) r[4];
            int phantomCount = ((Number) r[5]).intValue();
            BigDecimal target = toBig(r[6]);
            BigDecimal settled = toBig(r[7]);
            int internalCount = ((Number) r[8]).intValue();
            boolean hasCn = ((Number) r[9]).intValue() == 1;
            result.add(new SettlementGroupRow(
                    new SettlementGroupKey(clientUuid, companyUuid, year, month),
                    clientName, companyNames.getOrDefault(companyUuid, companyUuid),
                    target.setScale(2, RoundingMode.HALF_UP),
                    settled.setScale(2, RoundingMode.HALF_UP),
                    target.subtract(settled).setScale(2, RoundingMode.HALF_UP),
                    phantomCount, internalCount, false, hasCn));
        }
        return result;
    }

    /** Coerce a native-query numeric cell to BigDecimal (null -> ZERO; passthrough BigDecimal; else via double). */
    private static BigDecimal toBig(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return (o instanceof BigDecimal b) ? b : BigDecimal.valueOf(((Number) o).doubleValue());
    }

    /** Bulk uuid -> company name. Uses the Company Panache entity (same one Invoice.getCompany() returns). */
    private Map<String, String> resolveCompanyNames(Set<String> companyUuids) {
        Map<String, String> out = new HashMap<>();
        if (companyUuids.isEmpty()) return out;
        List<Company> companies = Company.list("uuid in ?1", companyUuids);
        for (Company c : companies) out.put(c.getUuid(), c.getName());
        return out;
    }

    /** In-scope, mapped phantom uuids for a group (read; own tx via self for a fresh snapshot). */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<String> listGroupPhantomUuids(SettlementGroupKey key) {
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery("""
                SELECT uuid FROM invoices
                WHERE type='PHANTOM' AND status='CREATED' AND economics_entry_number IS NOT NULL
                  AND internal_invoice_skip = 0
                  AND billing_client_uuid = :client AND companyuuid = :company
                  AND year = :year AND month = :month
                ORDER BY uuid
                """)
                .setParameter("client", key.billingClientUuid())
                .setParameter("company", key.debtorCompanyUuid())
                .setParameter("year", key.year())
                .setParameter("month", key.month())
                .getResultList();
        return ids;
    }

    /** Settled per (issuer, consultant) from the group's live internals' lines (signed). */
    @Transactional
    public List<SettlementDeltaCalculator.SettledLine> settledLinesForGroup(SettlementGroupKey key) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT s.companyuuid AS issuer, ii.consultantuuid AS consultant,
                       COALESCE(SUM(ii.hours*ii.rate),0) AS amount
                FROM invoices s
                JOIN invoiceitems ii ON ii.invoiceuuid = s.uuid
                WHERE s.type IN ('INTERNAL','INTERNAL_SERVICE')
                  AND s.status IN ('PENDING_REVIEW','QUEUED','CREATED')
                  AND s.settlement_billing_client_uuid = :client
                  AND s.settlement_debtor_companyuuid = :company
                  AND s.settlement_year = :year AND s.settlement_month = :month
                  AND ii.consultantuuid IS NOT NULL
                GROUP BY s.companyuuid, ii.consultantuuid
                """)
                .setParameter("client", key.billingClientUuid())
                .setParameter("company", key.debtorCompanyUuid())
                .setParameter("year", key.year())
                .setParameter("month", key.month())
                .getResultList();
        List<SettlementDeltaCalculator.SettledLine> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new SettlementDeltaCalculator.SettledLine((String) r[0], (String) r[1], toBig(r[2])));
        }
        return out;
    }

    /**
     * Per-(issuer, consultant) target/settled/delta for a group's Settle dialog. LAZY-COMPUTE,
     * not strictly read-only: for any group phantom with no attributions yet it calls
     * deriveForPhantom (REQUIRES_NEW), which persists AUTO attribution rows + stamps the phantom's
     * billing client — the same lazy-compute precedent as InvoiceService.previewInternal. MANUAL
     * attributions are never overwritten and re-running is idempotent. No settlement document is issued.
     *
     * <p>target is summed DIRECTLY from the signed persisted attributed_amount (Decision D1), keyed by
     * (issuer = consultant's company as-of the period, consultant) — NOT reconstructed through
     * InternalInvoiceLineGenerator. Phantom items are synthesized with hours=1, so the generator would
     * round the share FRACTION to 2dp and drift ~1% from Σ attributed_amount; summing the persisted
     * amounts makes the preview reconcile exactly with D1 for the cross-company portion. (The generator
     * is still the right tool for the Phase-5 settle WRITE path, where line shaping matters.) settled is
     * Σ signed line totals of the group's live internals. The fold (delta = target - settled, rollups)
     * is delegated to the pure SettlementDeltaCalculator. The REPEATABLE-READ snapshot trap is avoided
     * by re-reading just-derived rows via getInvoiceAttributionsInNewTx (a fresh tx), never a plain
     * outer-snapshot re-read.
     */
    @Transactional
    public SettlementGroupPreview previewGroup(SettlementGroupKey key, Set<String> excludedAttributionUuids) {
        Set<String> excluded = (excludedAttributionUuids != null) ? excludedAttributionUuids : Set.of();
        List<String> phantomUuids = self.listGroupPhantomUuids(key);

        List<InvoiceItemAttribution> unionAttrs = new ArrayList<>();
        for (String pid : phantomUuids) {
            Invoice phantom = Invoice.findById(pid);
            if (phantom == null) continue;
            List<InvoiceItemAttribution> attrs = invoiceAttributionService.getInvoiceAttributions(pid);
            if (attrs.isEmpty()) {
                phantomAttributionService.deriveForPhantom(pid);
                attrs = invoiceAttributionService.getInvoiceAttributionsInNewTx(pid);
            }
            unionAttrs.addAll(attrs);
        }

        // Resolve consultant companies as-of the group's period. The group key IS a (year, month), so
        // resolve against the first day of that month — deterministic and historically correct (no
        // dependence on which phantom sorts first, and no LocalDate.now() fallback for historical groups).
        Set<String> consultantUuids = new HashSet<>();
        for (InvoiceItemAttribution a : unionAttrs) {
            if (a.consultantUuid != null && !a.consultantUuid.isBlank()) consultantUuids.add(a.consultantUuid);
        }
        LocalDate asOf = LocalDate.of(key.year(), key.month(), 1);
        Map<String, String> userCompanies = userCompanyResolver.resolveCompanies(consultantUuids, asOf);

        // Target lines DIRECTLY from the persisted, signed attributed_amount (Decision D1), keyed by
        // (issuer = consultant's company, consultant). Drop rules mirror InternalInvoiceLineGenerator:
        // skip excluded attributions, unmapped consultants, and same-company consultants (a company never
        // transfer-prices to itself). Summing attributed_amount (not generator line.rate*line.hours)
        // avoids the hours=1 rounding amplification and reconciles exactly with the group row's D1 target.
        List<SettlementDeltaCalculator.TargetLine> targets = new ArrayList<>();
        for (InvoiceItemAttribution a : unionAttrs) {
            if (a.uuid != null && excluded.contains(a.uuid)) continue;
            if (a.consultantUuid == null || a.consultantUuid.isBlank()) continue;
            if (a.attributedAmount == null) continue;
            String issuer = userCompanies.get(a.consultantUuid);
            if (issuer == null) continue;                            // unmapped consultant
            if (issuer.equals(key.debtorCompanyUuid())) continue;    // same-company: not cross-company billable
            targets.add(new SettlementDeltaCalculator.TargetLine(
                    issuer, a.consultantUuid, a.attributedAmount.setScale(2, RoundingMode.HALF_UP)));
        }

        // allResolved mirrors previewInternal (InvoiceService:1047): every consultant must map to a
        // company (resolveCompanies omits unresolved consultants from the map).
        boolean allResolved = consultantUuids.stream().allMatch(userCompanies::containsKey);

        List<SettlementDeltaCalculator.SettledLine> settled = settledLinesForGroup(key);

        // Names for the DTO.
        Map<String, String> consultantNames = resolveConsultantNames(consultantUuids);
        Set<String> companyUuids = new HashSet<>();
        companyUuids.add(key.debtorCompanyUuid());
        for (SettlementDeltaCalculator.TargetLine tline : targets) companyUuids.add(tline.issuerCompanyUuid());
        for (SettlementDeltaCalculator.SettledLine s : settled) companyUuids.add(s.issuerCompanyUuid());
        Map<String, String> companyNames = resolveCompanyNames(companyUuids);

        return SettlementDeltaCalculator.compute(key, key.debtorCompanyUuid(), targets, settled,
                consultantNames, companyNames, allResolved);
    }

    /** Bulk consultant uuid -> "First Last". */
    private Map<String, String> resolveConsultantNames(Set<String> userUuids) {
        Map<String, String> out = new HashMap<>();
        if (userUuids.isEmpty()) return out;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT uuid, CONCAT(firstname,' ',lastname) FROM user WHERE uuid IN (:ids)")
                .setParameter("ids", userUuids).getResultList();
        for (Object[] r : rows) out.put((String) r[0], (String) r[1]);
        return out;
    }
}
