package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.AttributionAuditLog;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.dto.AcceptAttributionsRequest;
import dk.trustworks.intranet.aggregates.invoice.model.dto.AttributionResolution;
import dk.trustworks.intranet.aggregates.invoice.model.dto.ResolvedAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.dto.ResolvedItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.AttributionSource;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

        List<InvoiceItem> baseItems = invoice.invoiceitems.stream()
                .filter(ii -> ii.origin == InvoiceItemOrigin.BASE)
                .toList();
        List<InvoiceItem> calculatedItems = invoice.invoiceitems.stream()
                .filter(ii -> ii.origin == InvoiceItemOrigin.CALCULATED)
                .toList();

        for (InvoiceItem item : baseItems) {
            computeBaseItemAttribution(item, invoice);
        }

        if (!calculatedItems.isEmpty()) {
            Map<String, BigDecimal> baseDistribution = computeBaseDistribution(invoiceUuid);
            for (InvoiceItem item : calculatedItems) {
                computeCalculatedItemAttribution(item, baseDistribution);
            }
        }

        log.infof("computeAttributions: completed for invoice uuid=%s, items=%d",
                invoiceUuid, invoice.invoiceitems.size());
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

        List<InvoiceItem> baseItems = items.stream()
                .filter(ii -> ii.origin == InvoiceItemOrigin.BASE)
                .toList();
        List<InvoiceItem> calculatedItems = items.stream()
                .filter(ii -> ii.origin == InvoiceItemOrigin.CALCULATED)
                .toList();

        for (InvoiceItem item : baseItems) {
            computeBaseItemAttribution(item, invoice);
        }

        if (!calculatedItems.isEmpty()) {
            Map<String, BigDecimal> baseDistribution = computeBaseDistribution(invoiceUuid);
            for (InvoiceItem item : calculatedItems) {
                computeCalculatedItemAttribution(item, baseDistribution);
            }
        }

        log.infof("computeAttributionsFromItems: completed for invoice uuid=%s, items=%d",
                invoiceUuid, items.size());
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

        Map<String, BigDecimal> workShares = computeWorkShares(invoice.contractuuid, invoice.invoicedate);
        if (!workShares.isEmpty()) {
            createAttributionsFromShares(item.uuid, workShares, itemTotal, item.hours, true);
            return;
        }

        Map<String, BigDecimal> consultantShares = computeContractConsultantShares(
                invoice.contractuuid, invoice.invoicedate);
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
     * Compute actual hours logged per consultant for a specific project+month.
     * This matches the data used by InvoiceGenerator when creating draft items,
     * so the returned hours correspond to the original line item amounts.
     *
     * @return Map of consultant UUID → total hours logged on the project in the period
     */
    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> computeProjectWorkHours(String projectUuid, LocalDate invoiceDate) {
        if (projectUuid == null || projectUuid.isBlank()) {
            return Map.of();
        }
        LocalDate periodStart = invoiceDate.withDayOfMonth(1);
        LocalDate periodEnd = periodStart.plusMonths(1);

        List<Object[]> rows = em.createNativeQuery("""
                        SELECT w.useruuid, SUM(w.workduration) AS total_hours
                        FROM work w
                        WHERE w.projectuuid = :projectUuid
                          AND w.registered >= :periodStart
                          AND w.registered < :periodEnd
                          AND w.workduration > 0
                        GROUP BY w.useruuid
                        """)
                .setParameter("projectUuid", projectUuid)
                .setParameter("periodStart", periodStart)
                .setParameter("periodEnd", periodEnd)
                .getResultList();

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
     * Deterministic logic handles straightforward cases (HIGH confidence).
     * Ambiguous items are flagged for AI resolution or user review.
     */
    @Transactional
    public AttributionResolution resolveAttributions(String invoiceUuid) {
        Invoice invoice = Invoice.findById(invoiceUuid);
        if (invoice == null) throw new IllegalArgumentException("Invoice not found: " + invoiceUuid);

        List<InvoiceItem> currentItems = invoice.invoiceitems.stream()
            .filter(i -> "BASE".equals(i.origin.name()))
            .toList();

        // Compute project-level work hours (matches InvoiceGenerator's data source)
        LocalDate effectiveDate = invoice.invoicedate != null ? invoice.invoicedate : LocalDate.now();
        Map<String, BigDecimal> projectWorkHours = Map.of();
        if (invoice.projectuuid != null) {
            try {
                projectWorkHours = computeProjectWorkHours(invoice.projectuuid, effectiveDate);
            } catch (Exception e) {
                log.warnf("resolveAttributions: failed to compute project work hours for project=%s", invoice.projectuuid);
            }
        }
        // Fallback to contract-level work shares if no project data
        if (projectWorkHours.isEmpty() && invoice.contractuuid != null) {
            try {
                Map<String, BigDecimal> workShares = computeWorkShares(invoice.contractuuid, effectiveDate);
                // Convert percentages back to approximate hours using total invoice hours
                double totalInvoiceHours = currentItems.stream().mapToDouble(i -> i.hours).sum();
                if (!workShares.isEmpty() && totalInvoiceHours > 0) {
                    Map<String, BigDecimal> approxHours = new LinkedHashMap<>();
                    for (var entry : workShares.entrySet()) {
                        approxHours.put(entry.getKey(),
                            entry.getValue().multiply(BigDecimal.valueOf(totalInvoiceHours))
                                .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP));
                    }
                    projectWorkHours = approxHours;
                }
            } catch (Exception e) {
                log.warnf("resolveAttributions: failed to compute work shares for contract=%s", invoice.contractuuid);
            }
        }

        // Build represented consultants set (those with line items)
        Set<String> representedConsultants = currentItems.stream()
            .map(i -> i.consultantuuid)
            .filter(uuid -> uuid != null && !uuid.isBlank())
            .collect(Collectors.toSet());

        // Phase 1-2: Deterministic resolution with confidence scoring
        List<ResolvedItem> resolvedItems = new ArrayList<>();
        List<ResolvedItem> flaggedItems = new ArrayList<>();

        for (InvoiceItem item : currentItems) {
            ResolvedItem resolved = resolveItem(item, projectWorkHours, representedConsultants);
            if ("HIGH".equals(resolved.confidence())) {
                resolvedItems.add(resolved);
            } else {
                flaggedItems.add(resolved);
            }
        }

        // Phase 3: AI fallback for flagged items
        if (!flaggedItems.isEmpty()) {
            try {
                List<ResolvedItem> aiResults = aiService.analyzeAttributions(
                    buildAnalysisContext(invoice, currentItems, resolvedItems, flaggedItems,
                        projectWorkHours, representedConsultants)
                );
                flaggedItems = aiResults;
            } catch (Exception e) {
                log.warn("AI attribution analysis failed — items will require manual resolution", e);
            }
        }

        // Phase 4: Merge results
        List<ResolvedItem> allItems = new ArrayList<>(resolvedItems);
        allItems.addAll(flaggedItems);

        // Phase 5: Enrich with consultant names (single bulk query)
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
            item.confidence(), item.reasoning()
        )).toList();

        return new AttributionResolution(
            allItems,
            flaggedItems.isEmpty(),
            flaggedItems.size()
        );
    }

    /**
     * Resolve a single item deterministically.
     *
     * When the item has a consultantuuid:
     *   - That consultant gets their project work hours attributed directly
     *   - Any excess hours (item hours minus consultant's work) are distributed
     *     to consultants who logged work but have no line item (unrepresented)
     *   - If no unrepresented consultants exist, 100% goes to the named consultant
     *
     * When the item has no consultantuuid: flagged as LOW confidence for AI/manual review.
     */
    private ResolvedItem resolveItem(InvoiceItem item,
                                     Map<String, BigDecimal> projectWorkHours,
                                     Set<String> representedConsultants) {
        BigDecimal itemTotal = BigDecimal.valueOf(item.rate * item.hours);
        BigDecimal itemHours = BigDecimal.valueOf(item.hours);

        // Case 1: Item has a consultant UUID — attribute to that consultant
        if (item.consultantuuid != null && !item.consultantuuid.isBlank()) {

            // Find unrepresented consultants (logged work but no line item)
            Map<String, BigDecimal> unrepresented = projectWorkHours.entrySet().stream()
                .filter(e -> !representedConsultants.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                    (a, b) -> a, LinkedHashMap::new));

            BigDecimal consultantWorkHours = projectWorkHours.getOrDefault(
                item.consultantuuid, BigDecimal.ZERO);

            // If no unrepresented consultants or no work data, 100% to item's consultant
            if (unrepresented.isEmpty() || projectWorkHours.isEmpty()) {
                return new ResolvedItem(
                    item.uuid, item.itemname, itemHours, itemTotal,
                    List.of(new ResolvedAttribution(
                        item.consultantuuid, null,
                        BigDecimal.valueOf(100).setScale(PCT_SCALE, RoundingMode.HALF_UP),
                        itemTotal.setScale(AMT_SCALE, RoundingMode.HALF_UP),
                        itemHours.setScale(AMT_SCALE, RoundingMode.HALF_UP)
                    )),
                    "HIGH",
                    "Line item assigned to specific consultant"
                );
            }

            // There are unrepresented consultants — distribute excess hours to them.
            // The primary consultant gets at least their logged work hours.
            // Excess = item hours - consultant's work hours (clamped to >= 0).
            BigDecimal excess = itemHours.subtract(consultantWorkHours).max(BigDecimal.ZERO);

            // If no excess, 100% to the primary consultant
            if (excess.compareTo(BigDecimal.ZERO) == 0) {
                return new ResolvedItem(
                    item.uuid, item.itemname, itemHours, itemTotal,
                    List.of(new ResolvedAttribution(
                        item.consultantuuid, null,
                        BigDecimal.valueOf(100).setScale(PCT_SCALE, RoundingMode.HALF_UP),
                        itemTotal.setScale(AMT_SCALE, RoundingMode.HALF_UP),
                        itemHours.setScale(AMT_SCALE, RoundingMode.HALF_UP)
                    )),
                    "HIGH",
                    "Line item assigned to specific consultant"
                );
            }

            // Distribute excess proportionally among unrepresented consultants
            BigDecimal totalUnrepHours = unrepresented.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Cap excess to unrepresented total (don't attribute more than they worked)
            BigDecimal distributableExcess = excess.min(totalUnrepHours);
            BigDecimal primaryHours = itemHours.subtract(distributableExcess);

            BigDecimal primaryPct = primaryHours.multiply(BigDecimal.valueOf(100))
                .divide(itemHours, PCT_SCALE, RoundingMode.HALF_UP);
            BigDecimal primaryAmount = itemTotal.multiply(primaryPct)
                .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);

            List<ResolvedAttribution> attributions = new ArrayList<>();
            attributions.add(new ResolvedAttribution(
                item.consultantuuid, null,
                primaryPct,
                primaryAmount,
                primaryHours.setScale(AMT_SCALE, RoundingMode.HALF_UP)
            ));

            for (var entry : unrepresented.entrySet()) {
                BigDecimal unrepHours = entry.getValue();
                BigDecimal share = unrepHours.divide(totalUnrepHours, PCT_SCALE + 2, RoundingMode.HALF_UP);
                BigDecimal attrHours = distributableExcess.multiply(share)
                    .setScale(AMT_SCALE, RoundingMode.HALF_UP);
                BigDecimal attrPct = attrHours.multiply(BigDecimal.valueOf(100))
                    .divide(itemHours, PCT_SCALE, RoundingMode.HALF_UP);
                BigDecimal attrAmount = itemTotal.multiply(attrPct)
                    .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);

                attributions.add(new ResolvedAttribution(
                    entry.getKey(), null, attrPct, attrAmount, attrHours
                ));
            }

            return new ResolvedItem(
                item.uuid, item.itemname, itemHours, itemTotal,
                attributions, "HIGH",
                "Consultant's work: " + consultantWorkHours.setScale(1, RoundingMode.HALF_UP) +
                "h, excess " + distributableExcess.setScale(1, RoundingMode.HALF_UP) +
                "h redistributed to " + unrepresented.size() + " unrepresented consultant(s)"
            );
        }

        // Case 2: No consultant UUID — flag as LOW confidence
        return new ResolvedItem(
            item.uuid, item.itemname,
            BigDecimal.valueOf(item.hours), itemTotal,
            List.of(),
            "LOW",
            "No consultant assigned to this line item. May be a consolidated line."
        );
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

        return new InvoiceAttributionAIService.AnalysisContext(
            originalSnapshots,
            currentSnapshots,
            deletedSnapshots,
            workData,
            resolved,
            flagged
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

        // Delegate finalization to InvoiceService.createInvoice so this path
        // reuses the canonical logic: item recalculation, bonus recalc, e-conomic
        // draft creation (step 1), and PENDING_REVIEW status. Runs in the same transaction.
        return invoiceService.createInvoice(invoice);
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
}
