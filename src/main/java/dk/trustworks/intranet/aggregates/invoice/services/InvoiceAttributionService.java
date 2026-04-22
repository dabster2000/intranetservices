package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.AttributionAuditLog;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.dto.AcceptAttributionsRequest;
import dk.trustworks.intranet.aggregates.invoice.model.dto.AttributionResolution;
import dk.trustworks.intranet.aggregates.invoice.model.dto.DeltaAbsorptionResult;
import dk.trustworks.intranet.aggregates.invoice.model.dto.ResolvedAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.dto.ResolvedItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.AttributionSource;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import dk.trustworks.intranet.utils.DateUtils;

@JBossLog
@ApplicationScoped
public class InvoiceAttributionService {

    private static final int PCT_SCALE = 4;
    private static final int AMT_SCALE = 2;

    @Inject
    EntityManager em;

    @Inject
    InvoiceAttributionAIService aiService;

    @Inject
    InvoiceService invoiceService;

    @Inject
    UserCompanyResolver userCompanyResolver;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ── Engine baseline helper ────────────────────────────────────────

    /**
     * Build the DeltaAbsorptionEngine baseline for an invoice. Prefers the
     * persisted {@code baseline_snapshot} (stable reference captured at draft creation).
     * Falls back to computeProjectWorkHours for legacy drafts without a snapshot.
     */
    private Map<String, BigDecimal> buildEngineBaseline(Invoice invoice) {
        if (invoice.baselineSnapshot != null && !invoice.baselineSnapshot.isBlank()) {
            try {
                Map<String, Double> snapshot = objectMapper.readValue(
                        invoice.baselineSnapshot,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Double>>() {});
                Map<String, BigDecimal> result = new LinkedHashMap<>();
                for (var e : snapshot.entrySet()) {
                    result.put(e.getKey(), BigDecimal.valueOf(e.getValue()));
                }
                return result;
            } catch (Exception e) {
                log.warnf("buildEngineBaseline: failed to parse snapshot for invoice=%s, falling back",
                        invoice.uuid);
            }
        }
        // Fallback: derive from work table (legacy drafts with no snapshot).
        // Filter by BOTH contract and project — the same project name can belong
        // to multiple contracts on the /invoice sidebar, and filtering by project
        // alone pulls in work from a sibling contract that shares the project.
        // Use invoice.year/invoice.month as the billing period — NOT invoicedate,
        // which is the issue date (usually the month AFTER the work happened).
        if (invoice.projectuuid != null) {
            try {
                return computeProjectWorkHours(invoice.contractuuid,
                        invoice.projectuuid,
                        invoiceBillingPeriodStart(invoice));
            } catch (Exception e) {
                log.warnf("buildEngineBaseline: computeProjectWorkHours failed for project=%s",
                        invoice.projectuuid);
            }
        }
        return Map.of();
    }

    /**
     * The start of the invoice's billing period (first day of the month the
     * invoice covers). Uses invoice.year/invoice.month when populated —
     * these reflect the work period the invoice bills for (e.g. March) and
     * differ from invoicedate (the issue date, typically the following month).
     * Falls back to invoicedate, then today, for extremely old/malformed rows.
     */
    private LocalDate invoiceBillingPeriodStart(Invoice invoice) {
        if (invoice.year > 0 && invoice.month > 0) {
            return DateUtils.getFirstDayOfMonth(invoice.year, invoice.month);
        }
        if (invoice.invoicedate != null) {
            return invoice.invoicedate.withDayOfMonth(1);
        }
        return LocalDate.now().withDayOfMonth(1);
    }

    // ── Public query methods ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<InvoiceItemAttribution> getAttributions(String invoiceItemUuid) {
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT iia.uuid, iia.invoiceitem_uuid, iia.consultant_uuid,
                               iia.share_pct, iia.attributed_amount, iia.original_hours,
                               iia.source, iia.created_at, iia.updated_at,
                               CONCAT(u.firstname, ' ', u.lastname) AS consultant_name
                        FROM invoice_item_attributions iia
                        LEFT JOIN user u ON iia.consultant_uuid = u.uuid
                        WHERE iia.invoiceitem_uuid = :invoiceItemUuid
                        ORDER BY iia.share_pct DESC
                        """)
                .setParameter("invoiceItemUuid", invoiceItemUuid)
                .getResultList();
        return rows.stream().map(this::mapRowToAttribution).toList();
    }

    @SuppressWarnings("unchecked")
    public List<InvoiceItemAttribution> getInvoiceAttributions(String invoiceUuid) {
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT iia.uuid, iia.invoiceitem_uuid, iia.consultant_uuid,
                               iia.share_pct, iia.attributed_amount, iia.original_hours,
                               iia.source, iia.created_at, iia.updated_at,
                               CONCAT(u.firstname, ' ', u.lastname) AS consultant_name
                        FROM invoice_item_attributions iia
                        JOIN invoiceitems ii ON iia.invoiceitem_uuid = ii.uuid
                        LEFT JOIN user u ON iia.consultant_uuid = u.uuid
                        WHERE ii.invoiceuuid = :invoiceUuid
                        ORDER BY iia.invoiceitem_uuid, iia.share_pct DESC
                        """)
                .setParameter("invoiceUuid", invoiceUuid)
                .getResultList();
        return rows.stream().map(this::mapRowToAttribution).toList();
    }

    private InvoiceItemAttribution mapRowToAttribution(Object[] row) {
        var attr = new InvoiceItemAttribution();
        attr.uuid            = (String) row[0];
        attr.invoiceitemUuid = (String) row[1];
        attr.consultantUuid  = (String) row[2];
        attr.sharePct        = row[3] != null ? (BigDecimal) row[3] : null;
        attr.attributedAmount = row[4] != null ? (BigDecimal) row[4] : null;
        attr.originalHours   = row[5] != null ? (BigDecimal) row[5] : null;
        attr.source          = row[6] != null
                ? AttributionSource.valueOf((String) row[6]) : null;
        attr.createdAt       = row[7] != null ? (LocalDateTime) row[7] : null;
        attr.updatedAt       = row[8] != null ? (LocalDateTime) row[8] : null;
        attr.consultantName  = (String) row[9];
        return attr;
    }

    // ── Core attribution computation ──────────────────────────────────

    @Transactional
    public void computeAttributions(String invoiceUuid) {
        Invoice invoice = Invoice.findById(invoiceUuid);
        if (invoice == null) {
            log.warnf("computeAttributions: invoice not found uuid=%s", invoiceUuid);
            return;
        }

        // Invoice-level 409 guard: if ANY item on this source is referenced by a
        // finalized (CREATED/PENDING_REVIEW) linked internal invoice, we cannot
        // recompute at the invoice level — it would regenerate around frozen rows.
        // Per-item mutations still allow editing items that aren't frozen.
        Set<String> itemUuids = invoice.invoiceitems.stream()
                .map(ii -> ii.uuid)
                .collect(Collectors.toSet());
        assertNoFrozenInternalReferences(invoiceUuid, itemUuids);

        Map<Boolean, List<InvoiceItem>> partitioned = invoice.invoiceitems.stream()
                .collect(Collectors.partitioningBy(InvoiceItem::isEffectivelyCalculated));
        List<InvoiceItem> baseItems = partitioned.get(false);
        List<InvoiceItem> calculatedItems = partitioned.get(true);

        persistBaseAttributionsViaEngine(invoice, baseItems);

        if (!calculatedItems.isEmpty()) {
            Map<String, BigDecimal> baseDistribution = computeBaseDistribution(invoiceUuid);
            for (InvoiceItem item : calculatedItems) {
                computeCalculatedItemAttribution(item, baseDistribution);
            }
        }

        log.infof("computeAttributions: completed for invoice uuid=%s, items=%d",
                invoiceUuid, invoice.invoiceitems.size());

        cascadeToLinkedInternals(invoiceUuid, itemUuids);
    }

    /**
     * Computes attributions using a provided items list (avoids stale L1 cache).
     * Called from updateDraftInvoice where the invoice entity may be detached.
     */
    @Transactional
    public void computeAttributionsFromItems(String invoiceUuid, List<InvoiceItem> items) {
        Invoice invoice = Invoice.findById(invoiceUuid);
        if (invoice == null) {
            log.warnf("computeAttributionsFromItems: invoice not found uuid=%s", invoiceUuid);
            return;
        }

        Set<String> changedItemUuids = items.stream()
                .map(ii -> ii.uuid)
                .collect(Collectors.toSet());
        assertNoFrozenInternalReferences(invoiceUuid, changedItemUuids);

        Map<Boolean, List<InvoiceItem>> partitioned = items.stream()
                .collect(Collectors.partitioningBy(InvoiceItem::isEffectivelyCalculated));
        List<InvoiceItem> baseItems = partitioned.get(false);
        List<InvoiceItem> calculatedItems = partitioned.get(true);

        persistBaseAttributionsViaEngine(invoice, baseItems);

        if (!calculatedItems.isEmpty()) {
            Map<String, BigDecimal> baseDistribution = computeBaseDistribution(invoiceUuid);
            for (InvoiceItem item : calculatedItems) {
                computeCalculatedItemAttribution(item, baseDistribution);
            }
        }

        log.infof("computeAttributionsFromItems: completed for invoice uuid=%s, items=%d",
                invoiceUuid, items.size());

        cascadeToLinkedInternals(invoiceUuid, changedItemUuids);
    }

    // ── Recompute amounts from stable shares ──────────────────────────

    @Transactional
    public void recomputeItem(String invoiceItemUuid) {
        InvoiceItem item = InvoiceItem.findById(invoiceItemUuid);
        if (item == null) {
            log.warnf("recomputeItem: item not found uuid=%s", invoiceItemUuid);
            return;
        }

        double itemTotal = item.rate * item.hours;
        List<InvoiceItemAttribution> attributions = getAttributions(invoiceItemUuid);
        for (InvoiceItemAttribution attr : attributions) {
            attr.recalculateAmount(itemTotal);
            attr.updatedAt = java.time.LocalDateTime.now();
            em.merge(attr);
        }

        log.infof("recomputeItem: recalculated %d attributions for item uuid=%s, total=%.2f",
                attributions.size(), invoiceItemUuid, itemTotal);
    }

    // ── Manual attribution override ───────────────────────────────────

    public record ManualAttributionInput(String consultantUuid, BigDecimal sharePct) {}

    @Transactional
    public void setManualAttribution(String invoiceItemUuid, List<ManualAttributionInput> inputs) {
        InvoiceItem item = InvoiceItem.findById(invoiceItemUuid);
        if (item == null) {
            log.warnf("setManualAttribution: item not found uuid=%s", invoiceItemUuid);
            return;
        }

        InvoiceItemAttribution.delete("invoiceitemUuid", invoiceItemUuid);

        double itemTotal = item.rate * item.hours;
        for (ManualAttributionInput input : inputs) {
            BigDecimal amount = input.sharePct()
                    .multiply(BigDecimal.valueOf(itemTotal))
                    .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);
            var attribution = new InvoiceItemAttribution(
                    invoiceItemUuid,
                    input.consultantUuid(),
                    input.sharePct().setScale(PCT_SCALE, RoundingMode.HALF_UP),
                    amount,
                    null,
                    AttributionSource.MANUAL
            );
            attribution.persist();
        }

        log.infof("setManualAttribution: set %d manual attributions for item uuid=%s",
                inputs.size(), invoiceItemUuid);

        cascadeToLinkedInternals(item.invoiceuuid, Set.of(invoiceItemUuid));
    }

    // ── Reset to auto ─────────────────────────────────────────────────

    @Transactional
    public void resetToAuto(String invoiceItemUuid) {
        InvoiceItem item = InvoiceItem.findById(invoiceItemUuid);
        if (item == null) {
            log.warnf("resetToAuto: item not found uuid=%s", invoiceItemUuid);
            return;
        }

        InvoiceItemAttribution.delete("invoiceitemUuid", invoiceItemUuid);

        String invoiceUuid = item.invoiceuuid;
        Invoice invoice = Invoice.findById(invoiceUuid);
        if (invoice == null) {
            log.warnf("resetToAuto: invoice not found uuid=%s", invoiceUuid);
            return;
        }

        if (item.origin == InvoiceItemOrigin.BASE) {
            computeBaseItemAttribution(item, invoice);
        } else {
            Map<String, BigDecimal> baseDistribution = computeBaseDistribution(invoiceUuid);
            computeCalculatedItemAttribution(item, baseDistribution);
        }

        log.infof("resetToAuto: recomputed auto attributions for item uuid=%s", invoiceItemUuid);

        cascadeToLinkedInternals(invoiceUuid, Set.of(invoiceItemUuid));
    }

    // ── Batch backfill ────────────────────────────────────────────────

    @Transactional
    public int backfillRange(LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<String> invoiceUuids = em.createNativeQuery("""
                        SELECT i.uuid FROM invoices i
                        WHERE i.invoicedate >= :fromDate
                          AND i.invoicedate < :toDate
                          AND NOT EXISTS (
                            SELECT 1 FROM invoice_item_attributions iia
                            JOIN invoiceitems ii ON iia.invoiceitem_uuid = ii.uuid
                            WHERE ii.invoiceuuid = i.uuid
                          )
                        ORDER BY i.invoicedate
                        """)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .getResultList();

        int count = 0;
        for (String invoiceUuid : invoiceUuids) {
            computeAttributions(invoiceUuid);
            count++;
            if (count % 100 == 0) {
                em.flush();
                em.clear();
                log.infof("backfillRange: processed %d/%d invoices", count, invoiceUuids.size());
            }
        }

        log.infof("backfillRange: completed %d invoices for range %s to %s", count, from, to);
        return count;
    }

    // ── Find unattributed items ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findUnattributedItems(LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT ii.uuid AS item_uuid,
                               ii.invoiceuuid AS invoice_uuid,
                               i.invoicedate,
                               i.clientname,
                               ii.itemname,
                               (ii.rate * ii.hours) AS amount
                        FROM invoiceitems ii
                        JOIN invoices i ON ii.invoiceuuid = i.uuid
                        WHERE i.invoicedate >= :fromDate
                          AND i.invoicedate < :toDate
                          AND NOT EXISTS (
                            SELECT 1 FROM invoice_item_attributions iia
                            WHERE iia.invoiceitem_uuid = ii.uuid
                          )
                        ORDER BY i.invoicedate, ii.position
                        """)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .getResultList();

        return rows.stream()
                .map(row -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("itemUuid", row[0]);
                    map.put("invoiceUuid", row[1]);
                    map.put("invoiceDate", row[2]);
                    map.put("clientName", row[3]);
                    map.put("itemName", row[4]);
                    map.put("amount", row[5]);
                    return map;
                })
                .toList();
    }

    // ── Private: engine-based BASE attribution (canonical path) ──────────

    /**
     * Persist AUTO attributions for BASE items using DeltaAbsorptionEngine.
     * This is the canonical path; replaces per-item {@link #computeBaseItemAttribution}
     * attribution for items with a consultantuuid, because only a cross-item engine
     * can correctly handle deleted-consultant absorption.
     *
     * MANUAL attributions are preserved (only amount recalculated).
     * Items with null consultantuuid fall back to the old helper which handles
     * work-share and contract-consultant distribution.
     */
    private void persistBaseAttributionsViaEngine(Invoice invoice, List<InvoiceItem> baseItems) {
        List<InvoiceItem> itemsWithConsultant = new ArrayList<>();
        List<InvoiceItem> itemsWithoutConsultant = new ArrayList<>();
        for (InvoiceItem item : baseItems) {
            if (item.consultantuuid == null || item.consultantuuid.isBlank()) {
                itemsWithoutConsultant.add(item);
            } else {
                itemsWithConsultant.add(item);
            }
        }

        Map<String, BigDecimal> baseline = buildEngineBaseline(invoice);

        List<DeltaAbsorptionEngine.CurrentLine> engineInput = itemsWithConsultant.stream()
                .map(i -> new DeltaAbsorptionEngine.CurrentLine(
                        i.uuid, i.consultantuuid, BigDecimal.valueOf(i.hours)))
                .toList();

        DeltaAbsorptionResult engineResult = DeltaAbsorptionEngine.resolve(baseline, engineInput);

        log.infof("persistBaseAttributionsViaEngine: invoice=%s project=%s baselineConsultants=%d " +
                        "currentLines=%d deletedPool=%s totalDelta=%s unattributed=%s",
                invoice.uuid, invoice.projectuuid, baseline.size(), engineInput.size(),
                engineResult.totalDeletedPool(), engineResult.totalPositiveDelta(),
                engineResult.unattributedPoolHours());

        for (InvoiceItem item : itemsWithConsultant) {
            persistItemFromEngineResult(item, engineResult);
        }

        for (InvoiceItem item : itemsWithoutConsultant) {
            computeBaseItemAttribution(item, invoice);
        }
    }

    /**
     * Persist the engine's per-line shares for a single BASE item.
     * Preserves MANUAL attributions. Falls back to 100% line-consultant when
     * the engine produced no shares for this item.
     */
    private void persistItemFromEngineResult(InvoiceItem item, DeltaAbsorptionResult engineResult) {
        List<InvoiceItemAttribution> existing = getAttributions(item.uuid);
        boolean hasManual = existing.stream()
                .anyMatch(a -> a.source == AttributionSource.MANUAL);

        if (hasManual) {
            double itemTotal = item.rate * item.hours;
            for (InvoiceItemAttribution attr : existing) {
                attr.recalculateAmount(itemTotal);
                attr.updatedAt = java.time.LocalDateTime.now();
            }
            return;
        }

        InvoiceItemAttribution.delete(
                "invoiceitemUuid = ?1 AND source = ?2",
                item.uuid, AttributionSource.AUTO);

        double itemTotal = item.rate * item.hours;
        List<DeltaAbsorptionResult.ConsultantShare> shares =
                engineResult.attributionsByLine().getOrDefault(item.uuid, List.of());

        if (shares.isEmpty()) {
            createSingleAttribution(item.uuid, item.consultantuuid, itemTotal, item.hours);
            return;
        }

        BigDecimal itemHours = BigDecimal.valueOf(item.hours);
        BigDecimal itemTotalBD = BigDecimal.valueOf(itemTotal);
        for (DeltaAbsorptionResult.ConsultantShare share : shares) {
            BigDecimal sharePct = share.hours().multiply(BigDecimal.valueOf(100))
                    .divide(itemHours, PCT_SCALE, RoundingMode.HALF_UP);
            BigDecimal amount = itemTotalBD.multiply(sharePct)
                    .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);

            InvoiceItemAttribution attr = new InvoiceItemAttribution(
                    item.uuid,
                    share.consultantUuid(),
                    sharePct.setScale(PCT_SCALE, RoundingMode.HALF_UP),
                    amount,
                    share.hours().setScale(AMT_SCALE, RoundingMode.HALF_UP),
                    AttributionSource.AUTO
            );
            attr.persist();
        }
    }

    // ── Private: BASE item attribution ────────────────────────────────

    private void computeBaseItemAttribution(InvoiceItem item, Invoice invoice) {
        List<InvoiceItemAttribution> existing = getAttributions(item.uuid);
        boolean hasManual = existing.stream()
                .anyMatch(a -> a.source == AttributionSource.MANUAL);

        if (hasManual) {
            double itemTotal = item.rate * item.hours;
            for (InvoiceItemAttribution attr : existing) {
                attr.recalculateAmount(itemTotal);
                attr.updatedAt = java.time.LocalDateTime.now();
            }
            return;
        }

        InvoiceItemAttribution.delete(
                "invoiceitemUuid = ?1 AND source = ?2",
                item.uuid, AttributionSource.AUTO
        );

        double itemTotal = item.rate * item.hours;

        if (item.consultantuuid != null && !item.consultantuuid.isBlank()) {
            createSingleAttribution(item.uuid, item.consultantuuid, itemTotal, item.hours);
            return;
        }

        if (invoice.contractuuid == null || invoice.contractuuid.isBlank()) {
            log.warnf("computeBaseItemAttribution: no contractuuid on invoice uuid=%s, skipping item uuid=%s",
                    invoice.uuid, item.uuid);
            return;
        }

        // Preferred fallback: attribute proportional to the OTHER BASE items'
        // attributions on this invoice. This keeps the "eligibility" invariant
        // (only consultants represented on the invoice get attributed) and is
        // the right behaviour for discount-like items stored with origin=BASE
        // and null consultantuuid, as well as for genuine consolidated lines
        // where other BASE items already have consultant-specific attributions.
        Map<String, BigDecimal> invoiceBaseDistribution = computeBaseDistribution(invoice.uuid);
        if (!invoiceBaseDistribution.isEmpty()) {
            createAttributionsFromShares(item.uuid, invoiceBaseDistribution, itemTotal, false);
            return;
        }

        // No other BASE attributions to mirror — fall back to contract-wide work
        // shares (legacy behaviour, used when the invoice has ONLY null-consultant
        // items). Use the billing period (invoice.year/invoice.month), not
        // invoicedate, to query work data for the right month.
        LocalDate billingPeriod = invoiceBillingPeriodStart(invoice);
        Map<String, BigDecimal> workShares = computeWorkShares(invoice.contractuuid, billingPeriod);
        if (!workShares.isEmpty()) {
            createAttributionsFromShares(item.uuid, workShares, itemTotal, item.hours, true);
            return;
        }

        Map<String, BigDecimal> consultantShares = computeContractConsultantShares(
                invoice.contractuuid, billingPeriod);
        if (!consultantShares.isEmpty()) {
            createAttributionsFromShares(item.uuid, consultantShares, itemTotal, false);
            return;
        }

        log.warnf("computeBaseItemAttribution: no work data or contract consultants for " +
                        "contract=%s, invoice=%s, item=%s — item remains unattributed",
                invoice.contractuuid, invoice.uuid, item.uuid);
    }

    // ── Private: CALCULATED item attribution ──────────────────────────

    private void computeCalculatedItemAttribution(InvoiceItem item, Map<String, BigDecimal> baseDistribution) {
        List<InvoiceItemAttribution> existing = getAttributions(item.uuid);
        boolean hasManual = existing.stream()
                .anyMatch(a -> a.source == AttributionSource.MANUAL);

        if (hasManual) {
            double itemTotal = item.rate * item.hours;
            for (InvoiceItemAttribution attr : existing) {
                attr.recalculateAmount(itemTotal);
                attr.updatedAt = java.time.LocalDateTime.now();
            }
            return;
        }

        InvoiceItemAttribution.delete(
                "invoiceitemUuid = ?1 AND source = ?2",
                item.uuid, AttributionSource.AUTO
        );

        if (baseDistribution.isEmpty()) {
            log.warnf("computeCalculatedItemAttribution: no base distribution for item uuid=%s", item.uuid);
            return;
        }

        double itemTotal = item.rate * item.hours;
        createAttributionsFromShares(item.uuid, baseDistribution, itemTotal, false);
    }

    // ── Private: work-based share computation ─────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> computeWorkShares(String contractUuid, LocalDate invoiceDate) {
        LocalDate periodStart = invoiceDate.withDayOfMonth(1);
        LocalDate periodEnd = periodStart.plusMonths(1);

        List<Object[]> rows = em.createNativeQuery("""
                        SELECT w.useruuid, SUM(w.workduration) AS total_hours
                        FROM work w
                        JOIN task t ON w.taskuuid = t.uuid
                        JOIN contract_project cp ON t.projectuuid = cp.projectuuid
                        WHERE cp.contractuuid = :contractUuid
                          AND w.registered >= :periodStart
                          AND w.registered < :periodEnd
                          AND w.workduration > 0
                        GROUP BY w.useruuid
                        """)
                .setParameter("contractUuid", contractUuid)
                .setParameter("periodStart", periodStart)
                .setParameter("periodEnd", periodEnd)
                .getResultList();

        if (rows.isEmpty()) {
            return Map.of();
        }

        BigDecimal totalHours = BigDecimal.ZERO;
        Map<String, BigDecimal> hoursByConsultant = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String useruuid = (String) row[0];
            BigDecimal hours = toBigDecimal(row[1]);
            hoursByConsultant.put(useruuid, hours);
            totalHours = totalHours.add(hours);
        }

        if (totalHours.compareTo(BigDecimal.ZERO) == 0) {
            return Map.of();
        }

        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        for (var entry : hoursByConsultant.entrySet()) {
            BigDecimal pct = entry.getValue()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalHours, PCT_SCALE, RoundingMode.HALF_UP);
            shares.put(entry.getKey(), pct);
        }

        return shares;
    }

    // ── Private: project-level work hours (matches InvoiceGenerator logic) ──

    /**
     * Compute actual hours logged per consultant for a specific contract + project
     * in the billing period. Matches InvoiceGenerator.createDraftInvoiceFromProject
     * which filters by BOTH contractuuid AND projectuuid (line 165) — a single
     * project UUID can appear under multiple contracts on the /invoice sidebar,
     * so filtering by project alone over-collects work from a different contract
     * that happens to share the project name.
     *
     * @return Map of consultant UUID → total hours logged on (contract, project)
     *         in the period
     */
    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> computeProjectWorkHours(String contractUuid, String projectUuid, LocalDate periodStartDate) {
        if (projectUuid == null || projectUuid.isBlank()) {
            return Map.of();
        }
        LocalDate periodStart = periodStartDate.withDayOfMonth(1);
        LocalDate periodEnd = periodStart.plusMonths(1);

        StringBuilder sql = new StringBuilder("""
                SELECT w.useruuid, SUM(w.workduration) AS total_hours
                FROM work w
                WHERE w.projectuuid = :projectUuid
                  AND w.registered >= :periodStart
                  AND w.registered < :periodEnd
                  AND w.workduration > 0
                """);
        boolean hasContract = contractUuid != null && !contractUuid.isBlank();
        if (hasContract) {
            sql.append("  AND w.contractuuid = :contractUuid\n");
        }
        sql.append("GROUP BY w.useruuid");

        var query = em.createNativeQuery(sql.toString())
                .setParameter("projectUuid", projectUuid)
                .setParameter("periodStart", periodStart)
                .setParameter("periodEnd", periodEnd);
        if (hasContract) {
            query.setParameter("contractUuid", contractUuid);
        }
        List<Object[]> rows = query.getResultList();

        if (rows.isEmpty()) {
            return Map.of();
        }

        Map<String, BigDecimal> hoursByConsultant = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String useruuid = (String) row[0];
            BigDecimal hours = toBigDecimal(row[1]);
            hoursByConsultant.put(useruuid, hours);
        }
        return hoursByConsultant;
    }

    // ── Private: contract consultant fallback ─────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> computeContractConsultantShares(String contractUuid, LocalDate invoiceDate) {
        LocalDate periodStart = invoiceDate.withDayOfMonth(1);
        LocalDate periodEnd = periodStart.plusMonths(1).minusDays(1);

        List<String> consultantUuids = em.createNativeQuery("""
                        SELECT cc.useruuid
                        FROM contract_consultants cc
                        WHERE cc.contractuuid = :contractUuid
                          AND cc.activefrom <= :periodEnd
                          AND cc.activeto >= :periodStart
                        """)
                .setParameter("contractUuid", contractUuid)
                .setParameter("periodStart", periodStart)
                .setParameter("periodEnd", periodEnd)
                .getResultList();

        if (consultantUuids.isEmpty()) {
            return Map.of();
        }

        BigDecimal equalShare = BigDecimal.valueOf(100)
                .divide(BigDecimal.valueOf(consultantUuids.size()), PCT_SCALE, RoundingMode.HALF_UP);

        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        for (String uuid : consultantUuids) {
            shares.put(uuid, equalShare);
        }

        return shares;
    }

    // ── Private: base distribution across an invoice ──────────────────

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> computeBaseDistribution(String invoiceUuid) {
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT iia.consultant_uuid, SUM(iia.attributed_amount) AS total_amount
                        FROM invoice_item_attributions iia
                        JOIN invoiceitems ii ON iia.invoiceitem_uuid = ii.uuid
                        WHERE ii.invoiceuuid = :invoiceUuid
                          AND ii.origin = 'BASE'
                        GROUP BY iia.consultant_uuid
                        """)
                .setParameter("invoiceUuid", invoiceUuid)
                .getResultList();

        if (rows.isEmpty()) {
            return Map.of();
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<String, BigDecimal> amountByConsultant = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String consultantUuid = (String) row[0];
            BigDecimal amount = toBigDecimal(row[1]);
            amountByConsultant.put(consultantUuid, amount);
            totalAmount = totalAmount.add(amount);
        }

        if (totalAmount.compareTo(BigDecimal.ZERO) == 0) {
            return Map.of();
        }

        Map<String, BigDecimal> distribution = new LinkedHashMap<>();
        for (var entry : amountByConsultant.entrySet()) {
            BigDecimal pct = entry.getValue()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalAmount, PCT_SCALE, RoundingMode.HALF_UP);
            distribution.put(entry.getKey(), pct);
        }

        return distribution;
    }

    // ── Private: attribution creation helpers ─────────────────────────

    private void createSingleAttribution(String invoiceItemUuid, String consultantUuid, double itemTotal, double hours) {
        var attribution = new InvoiceItemAttribution(
                invoiceItemUuid,
                consultantUuid,
                BigDecimal.valueOf(100).setScale(PCT_SCALE, RoundingMode.HALF_UP),
                BigDecimal.valueOf(itemTotal).setScale(AMT_SCALE, RoundingMode.HALF_UP),
                BigDecimal.valueOf(hours),
                AttributionSource.AUTO
        );
        attribution.persist();
    }

    private void createAttributionsFromShares(
            String invoiceItemUuid,
            Map<String, BigDecimal> shares,
            double itemTotal,
            boolean includeHours) {
        createAttributionsFromShares(invoiceItemUuid, shares, itemTotal, 0, includeHours);
    }

    private void createAttributionsFromShares(
            String invoiceItemUuid,
            Map<String, BigDecimal> shares,
            double itemTotal,
            double totalHours,
            boolean includeHours) {
        for (var entry : shares.entrySet()) {
            BigDecimal amount = entry.getValue()
                    .multiply(BigDecimal.valueOf(itemTotal))
                    .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);

            BigDecimal originalHours = null;
            if (includeHours && totalHours > 0) {
                originalHours = entry.getValue()
                        .multiply(BigDecimal.valueOf(totalHours))
                        .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);
            }

            var attribution = new InvoiceItemAttribution(
                    invoiceItemUuid,
                    entry.getKey(),
                    entry.getValue().setScale(PCT_SCALE, RoundingMode.HALF_UP),
                    amount,
                    originalHours,
                    AttributionSource.AUTO
            );
            attribution.persist();
        }
    }

    // ── Private: type conversion ──────────────────────────────────────

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        BigDecimal left = a != null ? a : BigDecimal.ZERO;
        BigDecimal right = b != null ? b : BigDecimal.ZERO;
        return left.add(right);
    }

    // ── Private: consultant name lookup ────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, String> lookupConsultantNames(Set<String> userUuids) {
        if (userUuids.isEmpty()) return Map.of();
        List<Object[]> rows = em.createNativeQuery(
                "SELECT uuid, CONCAT(firstname, ' ', lastname) FROM user WHERE uuid IN (:uuids)")
            .setParameter("uuids", userUuids)
            .getResultList();
        Map<String, String> names = new HashMap<>();
        for (Object[] row : rows) {
            names.put((String) row[0], (String) row[1]);
        }
        return names;
    }

    // ── Resolve pipeline ────────────────────────────────────────────────

    /**
     * Phase 1-4 pipeline: Resolve attributions for all line items on an invoice.
     * Uses DeltaAbsorptionEngine for deterministic HIGH-confidence resolution.
     * Ambiguous items (no consultantuuid, no baseline) are flagged for AI resolution or user review.
     */
    @Transactional
    public AttributionResolution resolveAttributions(String invoiceUuid) {
        Invoice invoice = Invoice.findById(invoiceUuid);
        if (invoice == null) throw new IllegalArgumentException("Invoice not found: " + invoiceUuid);

        List<InvoiceItem> currentItems = invoice.invoiceitems.stream()
            .filter(i -> "BASE".equals(i.origin.name()))
            .toList();

        // 1. Build baseline (snapshot if present, else logged-work hours per consultant for this contract/project)
        Map<String, BigDecimal> baseline = buildEngineBaseline(invoice);

        // 2. Partition items: those with a consultantuuid feed the engine;
        //    those without are flagged LOW for AI/manual resolution.
        List<DeltaAbsorptionEngine.CurrentLine> engineInput = new ArrayList<>();
        List<InvoiceItem> itemsWithConsultant = new ArrayList<>();
        List<ResolvedItem> unresolved = new ArrayList<>();

        for (InvoiceItem item : currentItems) {
            if (item.consultantuuid == null || item.consultantuuid.isBlank()) {
                unresolved.add(new ResolvedItem(
                    item.uuid, item.itemname,
                    BigDecimal.valueOf(item.hours),
                    BigDecimal.valueOf(item.rate * item.hours),
                    List.of(), "LOW",
                    "No consultant assigned to this line item. May be a consolidated line."
                ));
                continue;
            }
            itemsWithConsultant.add(item);
            engineInput.add(new DeltaAbsorptionEngine.CurrentLine(
                item.uuid, item.consultantuuid, BigDecimal.valueOf(item.hours)));
        }

        // 3. Run the engine
        DeltaAbsorptionResult engineResult = DeltaAbsorptionEngine.resolve(baseline, engineInput);

        // 4. Convert engine output to ResolvedItem (per-line)
        List<ResolvedItem> resolvedItems = new ArrayList<>();
        for (InvoiceItem item : itemsWithConsultant) {
            resolvedItems.add(buildResolvedItem(item, engineResult, baseline));
        }

        // 5. If deterministic produced nothing (no consultant items or empty baseline with nulls), fall back to AI
        Set<String> representedConsultants = itemsWithConsultant.stream()
            .map(i -> i.consultantuuid).collect(Collectors.toSet());

        List<ResolvedItem> flagged = unresolved;
        if ((resolvedItems.isEmpty() && !currentItems.isEmpty()) || !unresolved.isEmpty()) {
            try {
                List<ResolvedItem> aiResults = aiService.analyzeAttributions(
                    buildAnalysisContext(invoice, currentItems, resolvedItems, unresolved,
                        baseline, representedConsultants));
                flagged = aiResults;
            } catch (Exception e) {
                log.warn("AI attribution analysis failed — items will require manual resolution", e);
            }
        }

        // 6. Enrich with consultant names (single bulk query)
        List<ResolvedItem> allItems = new ArrayList<>(resolvedItems);
        allItems.addAll(flagged);
        Set<String> allUuids = allItems.stream()
            .flatMap(ri -> ri.attributions().stream())
            .map(ResolvedAttribution::consultantUuid)
            .collect(Collectors.toSet());
        Map<String, String> nameMap = lookupConsultantNames(allUuids);

        allItems = allItems.stream().map(item -> new ResolvedItem(
            item.itemUuid(), item.description(), item.hours(), item.amount(),
            item.attributions().stream().map(a -> new ResolvedAttribution(
                a.consultantUuid(),
                a.consultantName() != null ? a.consultantName() : nameMap.getOrDefault(a.consultantUuid(), null),
                a.sharePct(), a.attributedAmount(), a.attributedHours()
            )).toList(),
            item.confidence(), item.reasoning(),
            item.baselineHours(), item.delta()
        )).toList();

        return new AttributionResolution(allItems, flagged.isEmpty(), flagged.size());
    }

    /**
     * Convert a DeltaAbsorptionResult line into a ResolvedItem. The engine
     * produced consultant shares in hours; we convert to sharePct + attributedAmount
     * based on the line's total value. Also populates baselineHours and delta for UI display.
     */
    private ResolvedItem buildResolvedItem(InvoiceItem item,
                                           DeltaAbsorptionResult engineResult,
                                           Map<String, BigDecimal> baseline) {
        BigDecimal itemHours = BigDecimal.valueOf(item.hours);
        BigDecimal itemTotal = BigDecimal.valueOf(item.rate * item.hours);
        BigDecimal baselineForConsultant = baseline.getOrDefault(item.consultantuuid, BigDecimal.ZERO);
        BigDecimal delta = itemHours.subtract(baselineForConsultant);

        List<DeltaAbsorptionResult.ConsultantShare> shares =
            engineResult.attributionsByLine().getOrDefault(item.uuid, List.of());

        List<ResolvedAttribution> atts = new ArrayList<>();
        for (DeltaAbsorptionResult.ConsultantShare share : shares) {
            BigDecimal sharePct = share.hours()
                .multiply(BigDecimal.valueOf(100))
                .divide(itemHours, PCT_SCALE, RoundingMode.HALF_UP);
            BigDecimal amount = itemTotal.multiply(sharePct)
                .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);
            atts.add(new ResolvedAttribution(share.consultantUuid(), null,
                sharePct, amount,
                share.hours().setScale(AMT_SCALE, RoundingMode.HALF_UP)));
        }

        String reasoning = atts.size() <= 1
            ? "Line attributed 100% to its consultant (untouched or no deleted pool)"
            : "Delta-based absorption: line absorbed from deleted-consultant pool";
        return new ResolvedItem(item.uuid, item.itemname, itemHours,
                                itemTotal.setScale(AMT_SCALE, RoundingMode.HALF_UP),
                                atts, "HIGH", reasoning,
                                baselineForConsultant, delta);
    }

    private InvoiceAttributionAIService.AnalysisContext buildAnalysisContext(
            Invoice invoice, List<InvoiceItem> currentItems,
            List<ResolvedItem> resolved, List<ResolvedItem> flagged,
            Map<String, BigDecimal> projectWorkHours,
            Set<String> representedConsultants) {

        // Consultant name lookup for snapshots
        Set<String> allUuids = new HashSet<>(projectWorkHours.keySet());
        currentItems.forEach(i -> { if (i.consultantuuid != null) allUuids.add(i.consultantuuid); });
        Map<String, String> nameMap = lookupConsultantNames(allUuids);

        // Current item snapshots
        List<InvoiceAttributionAIService.ItemSnapshot> currentSnapshots = currentItems.stream()
            .map(i -> new InvoiceAttributionAIService.ItemSnapshot(
                i.uuid, i.itemname, i.hours, i.rate, i.consultantuuid,
                nameMap.getOrDefault(i.consultantuuid, null)))
            .toList();

        // Reconstruct original items from project work data
        // Each consultant who logged work would have had their own line item in the original draft
        List<InvoiceAttributionAIService.ItemSnapshot> originalSnapshots = projectWorkHours.entrySet().stream()
            .map(e -> new InvoiceAttributionAIService.ItemSnapshot(
                "original-" + e.getKey(),
                nameMap.getOrDefault(e.getKey(), e.getKey()),
                e.getValue().doubleValue(),
                0.0,
                e.getKey(),
                nameMap.getOrDefault(e.getKey(), null)))
            .toList();

        // Deleted items = consultants who logged work but no longer have a line item
        List<InvoiceAttributionAIService.ItemSnapshot> deletedSnapshots = projectWorkHours.entrySet().stream()
            .filter(e -> !representedConsultants.contains(e.getKey()))
            .map(e -> new InvoiceAttributionAIService.ItemSnapshot(
                "deleted-" + e.getKey(),
                nameMap.getOrDefault(e.getKey(), e.getKey()),
                e.getValue().doubleValue(),
                0.0,
                e.getKey(),
                nameMap.getOrDefault(e.getKey(), null)))
            .toList();

        // Work data with actual hours (not percentages)
        Map<String, InvoiceAttributionAIService.ConsultantWork> workData = projectWorkHours.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> new InvoiceAttributionAIService.ConsultantWork(
                    e.getKey(),
                    nameMap.getOrDefault(e.getKey(), null),
                    e.getValue().doubleValue(),
                    "")
            ));

        // Eligibility set = baseline consultants ∪ current line consultants (with non-null consultantuuid)
        List<InvoiceAttributionAIService.EligibleConsultant> eligible = new ArrayList<>();
        Set<String> eligibleSeen = new HashSet<>();
        for (var e : projectWorkHours.entrySet()) {
            if (e.getValue() != null && e.getValue().signum() > 0 && eligibleSeen.add(e.getKey())) {
                eligible.add(new InvoiceAttributionAIService.EligibleConsultant(
                    e.getKey(), nameMap.get(e.getKey()), e.getValue()));
            }
        }
        for (InvoiceItem i : currentItems) {
            if (i.consultantuuid != null && !i.consultantuuid.isBlank() && eligibleSeen.add(i.consultantuuid)) {
                eligible.add(new InvoiceAttributionAIService.EligibleConsultant(
                    i.consultantuuid, nameMap.get(i.consultantuuid),
                    projectWorkHours.getOrDefault(i.consultantuuid, BigDecimal.ZERO)));
            }
        }

        // Per-line baseline + delta
        Map<String, BigDecimal> baselineByLine = new LinkedHashMap<>();
        Map<String, BigDecimal> deltaByLine = new LinkedHashMap<>();
        for (InvoiceItem i : currentItems) {
            BigDecimal baseline = i.consultantuuid != null
                ? projectWorkHours.getOrDefault(i.consultantuuid, BigDecimal.ZERO)
                : BigDecimal.ZERO;
            BigDecimal current = BigDecimal.valueOf(i.hours);
            baselineByLine.put(i.uuid, baseline);
            deltaByLine.put(i.uuid, current.subtract(baseline));
        }

        return new InvoiceAttributionAIService.AnalysisContext(
            originalSnapshots,
            currentSnapshots,
            deletedSnapshots,
            workData,
            resolved,
            flagged,
            eligible,
            baselineByLine,
            deltaByLine
        );
    }

    // ── Accept and finalize ─────────────────────────────────────────────

    /**
     * Run the AI-powered resolve pipeline and persist its results as the
     * invoice's current attributions. Called from finalize so the review modal
     * reflects AI-refined splits (not the deterministic stopgap).
     *
     * <p>Persistence mirrors {@link #acceptAndFinalize}: delete existing rows
     * per item, then insert fresh rows derived from the resolver's shares.
     */
    @Transactional
    public void resolveAndPersistAttributions(String invoiceUuid) {
        AttributionResolution resolution = resolveAttributions(invoiceUuid);

        for (ResolvedItem resolvedItem : resolution.items()) {
            InvoiceItem item = InvoiceItem.findById(resolvedItem.itemUuid());
            if (item == null) continue;

            InvoiceItemAttribution.delete("invoiceitemUuid", resolvedItem.itemUuid());

            BigDecimal itemTotal = BigDecimal.valueOf(item.rate * item.hours);
            for (ResolvedAttribution share : resolvedItem.attributions()) {
                if (share.consultantUuid() == null || share.consultantUuid().isBlank()) continue;
                BigDecimal sharePct = share.sharePct() != null
                    ? share.sharePct().setScale(PCT_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
                BigDecimal amount = share.attributedAmount() != null
                    ? share.attributedAmount().setScale(AMT_SCALE, RoundingMode.HALF_UP)
                    : itemTotal.multiply(sharePct)
                        .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);
                BigDecimal originalHours = share.attributedHours() != null
                    ? share.attributedHours().setScale(AMT_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(item.hours).multiply(sharePct)
                        .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);

                InvoiceItemAttribution attr = new InvoiceItemAttribution(
                    resolvedItem.itemUuid(),
                    share.consultantUuid(),
                    sharePct,
                    amount,
                    originalHours,
                    AttributionSource.AUTO
                );
                attr.persist();
            }
        }

        log.infof("resolveAndPersistAttributions: invoice=%s items=%d flagged=%d",
                invoiceUuid, resolution.items().size(), resolution.flaggedCount());
    }

    /**
     * Atomically: save resolved attributions + transition DRAFT → CREATED.
     * Called by the wizard's "Create Invoice" button.
     */
    @Transactional
    public Invoice acceptAndFinalize(String invoiceUuid, AcceptAttributionsRequest request, String changedByUuid) {
        Invoice invoice = Invoice.findById(invoiceUuid);
        if (invoice == null) throw new IllegalArgumentException("Invoice not found: " + invoiceUuid);
        if (invoice.status != InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Can only finalize DRAFT invoices, current status: " + invoice.status);
        }

        for (AcceptAttributionsRequest.ItemAttribution itemAttr : request.items()) {
            List<InvoiceItemAttribution> oldAttrs = getAttributions(itemAttr.itemUuid());
            String oldState = serializeAttributions(oldAttrs);

            InvoiceItemAttribution.delete("invoiceitemUuid", itemAttr.itemUuid());

            InvoiceItem item = InvoiceItem.findById(itemAttr.itemUuid());
            if (item == null) continue;
            BigDecimal itemTotal = BigDecimal.valueOf(item.rate * item.hours);

            List<InvoiceItemAttribution> newAttrs = new ArrayList<>();
            for (AcceptAttributionsRequest.ConsultantShare share : itemAttr.attributions()) {
                BigDecimal amount = itemTotal.multiply(share.sharePct())
                    .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);
                BigDecimal originalHours = BigDecimal.valueOf(item.hours)
                    .multiply(share.sharePct())
                    .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);

                InvoiceItemAttribution attr = new InvoiceItemAttribution(
                    itemAttr.itemUuid(),
                    share.consultantUuid(),
                    share.sharePct().setScale(PCT_SCALE, RoundingMode.HALF_UP),
                    amount,
                    originalHours,
                    AttributionSource.AUTO
                );
                attr.persist();
                newAttrs.add(attr);
            }

            String newState = serializeAttributions(newAttrs);
            new AttributionAuditLog(
                invoiceUuid, itemAttr.itemUuid(), changedByUuid,
                "AUTO_COMPUTED", oldState, newState, null
            ).persist();
        }

        // Defensive guard: finalization intentionally skips cascadeToLinkedInternals
        // (spec §5.3 — finalized sources are immutable). But if a DRAFT/QUEUED linked
        // internal invoice already exists against this source, it won't be regenerated
        // with the finalization-time attribution and may be stale. WARN only — do not
        // block finalization (per QA-review fix 2, 2026-04-22).
        List<Invoice> linkedInternals = Invoice.find(
                "invoiceRefUuid = ?1 and type = ?2",
                invoiceUuid, InvoiceType.INTERNAL).list();
        for (Invoice linkedInvoice : linkedInternals) {
            if (linkedInvoice.status == InvoiceStatus.DRAFT
                    || linkedInvoice.status == InvoiceStatus.QUEUED) {
                log.warnf("Cascade skipped for finalized source %s but DRAFT/QUEUED internal %s exists — possibly stale",
                        invoiceUuid, linkedInvoice.uuid);
            }
        }

        // Delegate finalization to InvoiceService.createInvoice so this path
        // reuses the canonical logic: item recalculation, bonus recalc, e-conomic
        // draft creation (step 1), and PENDING_REVIEW status. Runs in the same transaction.
        return invoiceService.createInvoice(invoice);
    }

    /**
     * Admin-only: force recompute attributions on ANY-status invoice. Wipes both
     * MANUAL and AUTO rows, regenerates via the engine, writes an audit log entry.
     * Still respects the frozen-internals guard: if any linked internal invoice is
     * CREATED or PENDING_REVIEW, throws 409 Conflict.
     *
     * Unlike {@link #computeAttributions(String)}, this bypasses any status gate
     * and deliberately overwrites MANUAL attributions. Caller is responsible for
     * ensuring the request is authorized (admin scope).
     *
     * @param invoiceUuid    the invoice to recompute
     * @param changedByUuid  the admin user UUID (from X-Requested-By), used in audit log
     * @return fresh attribution rows after recompute
     */
    @Transactional
    public List<InvoiceItemAttribution> adminForceRecomputeAttributions(
            String invoiceUuid, String changedByUuid) {
        Invoice invoice = Invoice.findById(invoiceUuid);
        if (invoice == null) {
            throw new WebApplicationException("Invoice not found: " + invoiceUuid,
                    Response.Status.NOT_FOUND);
        }

        Map<String, List<InvoiceItemAttribution>> oldByItem = snapshotByItem(invoiceUuid);

        if (invoice.type == InvoiceType.CREDIT_NOTE) {
            copyAttributionsFromSource(invoice);
            writeAdminAuditLog(invoice, oldByItem, changedByUuid);
            log.infof("adminForceRecomputeAttributions (CN): invoice=%s by=%s items=%d",
                    invoice.uuid, changedByUuid, invoice.invoiceitems.size());
            return getInvoiceAttributions(invoice.uuid);
        }

        Set<String> itemUuids = invoice.invoiceitems.stream()
                .map(ii -> ii.uuid)
                .collect(Collectors.toSet());
        assertNoFrozenInternalReferences(invoiceUuid, itemUuids);

        for (InvoiceItem item : invoice.invoiceitems) {
            InvoiceItemAttribution.delete("invoiceitemUuid", item.uuid);
        }

        Map<Boolean, List<InvoiceItem>> partitioned = invoice.invoiceitems.stream()
                .collect(Collectors.partitioningBy(InvoiceItem::isEffectivelyCalculated));
        List<InvoiceItem> baseItems = partitioned.get(false);
        List<InvoiceItem> calculatedItems = partitioned.get(true);

        persistBaseAttributionsViaEngine(invoice, baseItems);

        if (!calculatedItems.isEmpty()) {
            Map<String, BigDecimal> baseDistribution = computeBaseDistribution(invoiceUuid);
            for (InvoiceItem item : calculatedItems) {
                computeCalculatedItemAttribution(item, baseDistribution);
            }
        }

        writeAdminAuditLog(invoice, oldByItem, changedByUuid);
        cascadeToLinkedInternals(invoiceUuid, itemUuids);

        log.infof("adminForceRecomputeAttributions: invoice=%s by=%s items=%d",
                invoiceUuid, changedByUuid, invoice.invoiceitems.size());

        return getInvoiceAttributions(invoiceUuid);
    }

    /** Fetch every attribution on the invoice in one query and index by item UUID. */
    private Map<String, List<InvoiceItemAttribution>> snapshotByItem(String invoiceUuid) {
        return getInvoiceAttributions(invoiceUuid).stream()
                .collect(Collectors.groupingBy(a -> a.invoiceitemUuid, LinkedHashMap::new, Collectors.toList()));
    }

    /** Write one ADMIN_FORCE_RECOMPUTE audit row per item, diffing against the prior snapshot. */
    private void writeAdminAuditLog(Invoice invoice,
                                    Map<String, List<InvoiceItemAttribution>> oldByItem,
                                    String changedByUuid) {
        Map<String, List<InvoiceItemAttribution>> newByItem = snapshotByItem(invoice.uuid);
        for (InvoiceItem item : invoice.invoiceitems) {
            String oldState = serializeAttributions(oldByItem.getOrDefault(item.uuid, List.of()));
            String newState = serializeAttributions(newByItem.getOrDefault(item.uuid, List.of()));
            new AttributionAuditLog(
                    invoice.uuid, item.uuid, changedByUuid,
                    "ADMIN_FORCE_RECOMPUTE", oldState, newState, null
            ).persist();
        }
    }

    private String serializeAttributions(List<InvoiceItemAttribution> attrs) {
        try {
            return objectMapper.writeValueAsString(
                attrs.stream().map(a -> Map.of(
                    "consultantUuid", a.consultantUuid,
                    "sharePct", a.sharePct,
                    "attributedAmount", a.attributedAmount
                )).toList()
            );
        } catch (Exception e) {
            return "[]";
        }
    }

    // ── Credit note attribution copy ──────────────────────────────────────

    /**
     * Copy attribution rows from a source invoice to its credit note, one-to-one
     * per line item. Used both at CN creation time and at admin-triggered recompute.
     *
     * Matching strategy:
     *   1. Prefer {@code cnItem.sourceItemUuid} (set on new CNs by createCreditNote).
     *   2. Fall back to fuzzy match on (consultantuuid, itemname, description, rate, hours)
     *      for older CNs that predate V300 and don't have sourceItemUuid set.
     *
     * The CN attribution mirrors the source's per-consultant shares; amounts stay
     * positive and the CN's type flag handles sign inversion at read time.
     * Writes {@link AttributionSource#AUTO} rows. MANUAL rows are NOT preserved here —
     * callers that need preservation should handle it before calling.
     */
    @Transactional
    public void copyAttributionsFromSource(Invoice creditNote) {
        if (creditNote == null || creditNote.type != InvoiceType.CREDIT_NOTE) {
            return;
        }
        String sourceUuid = creditNote.getCreditnoteForUuid();
        if (sourceUuid == null || sourceUuid.isBlank()) {
            log.warnf("copyAttributionsFromSource: CN %s has no creditnoteForUuid; skipping",
                    creditNote.uuid);
            return;
        }
        Invoice source = Invoice.findById(sourceUuid);
        if (source == null) {
            log.warnf("copyAttributionsFromSource: CN %s references missing source %s",
                    creditNote.uuid, sourceUuid);
            return;
        }

        int matched = 0;
        int unmatched = 0;
        for (InvoiceItem cnItem : creditNote.invoiceitems) {
            InvoiceItem sourceItem = findMatchingSourceItem(cnItem, source);
            if (sourceItem == null) {
                log.warnf("copyAttributionsFromSource: CN %s item %s has no source match",
                        creditNote.uuid, cnItem.uuid);
                unmatched++;
                continue;
            }

            List<InvoiceItemAttribution> sourceAttrs = getAttributions(sourceItem.uuid);
            InvoiceItemAttribution.delete("invoiceitemUuid", cnItem.uuid);

            BigDecimal cnItemHours = BigDecimal.valueOf(cnItem.hours);
            BigDecimal cnItemTotal = BigDecimal.valueOf(cnItem.rate * cnItem.hours);
            for (InvoiceItemAttribution src : sourceAttrs) {
                BigDecimal sharePct = src.sharePct;
                BigDecimal amount = cnItemTotal.multiply(sharePct)
                        .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);
                BigDecimal originalHours = cnItemHours.multiply(sharePct)
                        .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);
                InvoiceItemAttribution newAttr = new InvoiceItemAttribution(
                        cnItem.uuid,
                        src.consultantUuid,
                        sharePct,
                        amount,
                        originalHours,
                        AttributionSource.AUTO);
                newAttr.persist();
            }
            matched++;
        }
        log.infof("copyAttributionsFromSource: creditNote=%s source=%s matched=%d unmatched=%d",
                creditNote.uuid, sourceUuid, matched, unmatched);
    }

    /**
     * Find the source invoice item that corresponds to a CN item.
     * Prefers explicit {@code sourceItemUuid} linkage; falls back to fuzzy match on
     * (consultantuuid, itemname, description, rate, hours).
     */
    private InvoiceItem findMatchingSourceItem(InvoiceItem cnItem, Invoice source) {
        if (cnItem.sourceItemUuid != null && !cnItem.sourceItemUuid.isBlank()) {
            for (InvoiceItem s : source.invoiceitems) {
                if (cnItem.sourceItemUuid.equals(s.uuid)) return s;
            }
            // fall through to fuzzy match if explicit link didn't resolve
        }
        for (InvoiceItem s : source.invoiceitems) {
            if (!java.util.Objects.equals(cnItem.consultantuuid, s.consultantuuid)) continue;
            if (!java.util.Objects.equals(cnItem.itemname, s.itemname)) continue;
            if (!java.util.Objects.equals(cnItem.description, s.description)) continue;
            if (cnItem.rate != s.rate) continue;
            if (cnItem.hours != s.hours) continue;
            return s;
        }
        return null;
    }

    // ── Cascade to linked internal invoices (spec §5.3) ───────────────────

    /**
     * Throw 409 Conflict if any of {@code itemUuids} is referenced via
     * {@code source_item_uuid} on a line of a CREATED or PENDING_REVIEW linked
     * internal invoice. Called from {@code computeAttributions} for the invoice-level
     * guard; per-item mutations use {@link #cascadeToLinkedInternals} which performs
     * the same check with per-item granularity.
     */
    private void assertNoFrozenInternalReferences(String sourceInvoiceUuid, Set<String> itemUuids) {
        if (itemUuids == null || itemUuids.isEmpty()) return;

        List<Invoice> linked = Invoice.find(
                "invoiceRefUuid = ?1 and type = ?2",
                sourceInvoiceUuid, InvoiceType.INTERNAL).list();
        for (Invoice linkedInvoice : linked) {
            if (linkedInvoice.status != InvoiceStatus.CREATED
                    && linkedInvoice.status != InvoiceStatus.PENDING_REVIEW) {
                continue;
            }
            boolean conflicts = linkedInvoice.invoiceitems.stream()
                    .anyMatch(li -> li.sourceItemUuid != null
                            && itemUuids.contains(li.sourceItemUuid));
            if (conflicts) {
                throw new WebApplicationException(
                        "Attribution cannot be modified: item contributed to finalized internal invoice "
                                + linkedInvoice.invoicenumber
                                + ". Use a credit note to correct.",
                        Response.Status.CONFLICT);
            }
        }
    }

    /**
     * After persisting attribution changes on a source invoice's items, propagate
     * those changes to any linked INTERNAL invoice in DRAFT or QUEUED status:
     * delete current items and regenerate from {@link InternalInvoiceLineGenerator}
     * filtered to the linked invoice's issuer company. Skip CREATED/PENDING_REVIEW
     * invoices that don't reference any of the changed items; throw 409 Conflict
     * if they DO reference a changed item (frozen invoices cannot be edited).
     *
     * <p>Audit: writes one {@link AttributionAuditLog} row per changed item with
     * {@code changeType = "INTERNAL_INVOICE_REGEN"} and {@code changedBy} resolved
     * from the request's {@code X-Requested-By} header (fallback
     * {@code "system-cascade"} when no request context, e.g. during batch jobs).
     *
     * @param sourceInvoiceUuid the source invoice whose attribution was mutated
     * @param changedItemUuids  the subset of source-item UUIDs whose attribution changed
     */
    private void cascadeToLinkedInternals(String sourceInvoiceUuid, Set<String> changedItemUuids) {
        if (changedItemUuids == null || changedItemUuids.isEmpty()) return;

        List<Invoice> linkedInternals = Invoice.find(
                "invoiceRefUuid = ?1 and type = ?2",
                sourceInvoiceUuid, InvoiceType.INTERNAL).list();
        if (linkedInternals.isEmpty()) return;

        // Phase 1: check for frozen invoices that reference changed items → 409.
        for (Invoice linkedInvoice : linkedInternals) {
            if (linkedInvoice.status != InvoiceStatus.CREATED
                    && linkedInvoice.status != InvoiceStatus.PENDING_REVIEW) {
                continue;
            }
            boolean conflicts = linkedInvoice.invoiceitems.stream()
                    .anyMatch(li -> li.sourceItemUuid != null
                            && changedItemUuids.contains(li.sourceItemUuid));
            if (conflicts) {
                throw new WebApplicationException(
                        "Attribution cannot be modified: item contributed to finalized internal invoice "
                                + linkedInvoice.invoicenumber
                                + ". Use a credit note to correct.",
                        Response.Status.CONFLICT);
            }
        }

        // Phase 2: regenerate DRAFT/QUEUED linked internals from current attribution.
        Invoice source = Invoice.findById(sourceInvoiceUuid);
        if (source == null) {
            log.warnf("cascadeToLinkedInternals: source invoice not found uuid=%s", sourceInvoiceUuid);
            return;
        }

        List<InvoiceItemAttribution> attributions = getInvoiceAttributions(sourceInvoiceUuid);
        Set<String> consultantUuids = attributions.stream()
                .map(a -> a.consultantUuid)
                .filter(u -> u != null && !u.isBlank())
                .collect(Collectors.toSet());
        LocalDate asOf = source.invoicedate != null ? source.invoicedate : LocalDate.now();
        Map<String, String> userCompanies = userCompanyResolver.resolveCompanies(consultantUuids, asOf);
        String sourceCompanyUuid = source.company != null ? source.company.getUuid() : null;

        Map<String, List<InvoiceItem>> grouped = InternalInvoiceLineGenerator.generate(
                sourceCompanyUuid, source.invoiceitems, attributions, userCompanies);

        for (Invoice linkedInvoice : linkedInternals) {
            if (linkedInvoice.status != InvoiceStatus.DRAFT
                    && linkedInvoice.status != InvoiceStatus.QUEUED) {
                continue;
            }

            String issuerUuid = linkedInvoice.company != null ? linkedInvoice.company.getUuid() : null;
            if (issuerUuid == null) continue;

            List<InvoiceItem> newLines = grouped.getOrDefault(issuerUuid, List.of());

            // Snapshot old state for audit log BEFORE deleting.
            long previousCount = linkedInvoice.invoiceitems.size();

            // Delete existing items and persist regenerated ones.
            InvoiceItem.delete("invoiceuuid", linkedInvoice.uuid);
            linkedInvoice.invoiceitems.clear();

            int position = 1;
            for (InvoiceItem line : newLines) {
                line.invoiceuuid = linkedInvoice.uuid;
                line.position = position++;
                InvoiceItem.persist(line);
                linkedInvoice.invoiceitems.add(line);
            }

            log.infof("cascadeToLinkedInternals: invoice=%s issuer=%s items %d -> %d",
                    linkedInvoice.uuid, issuerUuid, previousCount, newLines.size());
        }

        // Phase 3: audit log — one row per changed item with INTERNAL_INVOICE_REGEN.
        String changedBy = resolveChangedBy();
        for (String itemUuid : changedItemUuids) {
            List<InvoiceItemAttribution> current = getAttributions(itemUuid);
            String stateJson = serializeAttributions(current);
            new AttributionAuditLog(
                    sourceInvoiceUuid,
                    itemUuid,
                    changedBy,
                    "INTERNAL_INVOICE_REGEN",
                    stateJson,
                    stateJson,
                    "cascade regenerated linked internal invoices"
            ).persist();
        }
    }

    /**
     * Resolve the {@code changedBy} UUID for audit logging. Uses the request-scoped
     * {@code X-Requested-By} header when available. Falls back to {@code "system-cascade"}
     * when invoked outside a request context (batch jobs). {@code changedBy} is a
     * NOT NULL column so the fallback is required.
     */
    private String resolveChangedBy() {
        try {
            String fromHeader = requestHeaderHolder.getUserUuid();
            if (fromHeader != null && !fromHeader.isBlank()) {
                return fromHeader;
            }
        } catch (Exception e) {
            // Request scope not active — fall through to the default.
        }
        return "system-cascade";
    }
}
