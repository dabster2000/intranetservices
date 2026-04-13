package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
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

    // ── Merge attributions ────────────────────────────────────────────

    @Transactional
    public void mergeAttributions(String targetItemUuid, String sourceItemUuid) {
        InvoiceItem targetItem = InvoiceItem.findById(targetItemUuid);
        if (targetItem == null) {
            log.warnf("mergeAttributions: target item not found uuid=%s", targetItemUuid);
            return;
        }

        List<InvoiceItemAttribution> targetAttrs = getAttributions(targetItemUuid);
        List<InvoiceItemAttribution> sourceAttrs = getAttributions(sourceItemUuid);

        Map<String, InvoiceItemAttribution> targetByConsultant = targetAttrs.stream()
                .collect(Collectors.toMap(a -> a.consultantUuid, a -> a));

        for (InvoiceItemAttribution sourceAttr : sourceAttrs) {
            InvoiceItemAttribution existing = targetByConsultant.get(sourceAttr.consultantUuid);
            if (existing != null) {
                BigDecimal combinedHours = safeAdd(existing.originalHours, sourceAttr.originalHours);
                existing.originalHours = combinedHours;
            } else {
                var merged = new InvoiceItemAttribution(
                        targetItemUuid,
                        sourceAttr.consultantUuid,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        sourceAttr.originalHours,
                        AttributionSource.AUTO
                );
                merged.persist();
                targetByConsultant.put(sourceAttr.consultantUuid, merged);
            }
        }

        double itemTotal = targetItem.rate * targetItem.hours;
        recalculateSharesFromHours(targetByConsultant.values(), itemTotal);

        InvoiceItemAttribution.delete("invoiceitemUuid", sourceItemUuid);

        log.infof("mergeAttributions: merged source=%s into target=%s, consultants=%d",
                sourceItemUuid, targetItemUuid, targetByConsultant.size());
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
            createAttributionsFromShares(item.uuid, workShares, itemTotal, true);
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
        for (var entry : shares.entrySet()) {
            BigDecimal amount = entry.getValue()
                    .multiply(BigDecimal.valueOf(itemTotal))
                    .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);

            var attribution = new InvoiceItemAttribution(
                    invoiceItemUuid,
                    entry.getKey(),
                    entry.getValue().setScale(PCT_SCALE, RoundingMode.HALF_UP),
                    amount,
                    null,
                    AttributionSource.AUTO
            );
            attribution.persist();
        }
    }

    // ── Private: recalculate from hours after merge ───────────────────

    private void recalculateSharesFromHours(Collection<InvoiceItemAttribution> attributions, double itemTotal) {
        BigDecimal totalHours = attributions.stream()
                .map(a -> a.originalHours != null ? a.originalHours : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalHours.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal equalShare = BigDecimal.valueOf(100)
                    .divide(BigDecimal.valueOf(attributions.size()), PCT_SCALE, RoundingMode.HALF_UP);
            for (InvoiceItemAttribution attr : attributions) {
                attr.sharePct = equalShare;
                attr.recalculateAmount(itemTotal);
                attr.updatedAt = java.time.LocalDateTime.now();
            }
            return;
        }

        for (InvoiceItemAttribution attr : attributions) {
            BigDecimal hours = attr.originalHours != null ? attr.originalHours : BigDecimal.ZERO;
            attr.sharePct = hours
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalHours, PCT_SCALE, RoundingMode.HALF_UP);
            attr.recalculateAmount(itemTotal);
            attr.updatedAt = java.time.LocalDateTime.now();
            em.merge(attr);
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
}
