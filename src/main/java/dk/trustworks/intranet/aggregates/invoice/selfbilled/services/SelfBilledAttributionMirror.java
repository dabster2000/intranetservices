package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.enums.AttributionSource;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledAssignment;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §6.3 attribution write-back: mirrors human assignments onto the imported PHANTOM
 * invoices' per-consultant attributions (source SELFBILLED_ASSIGNMENT), so
 * per-consultant revenue reporting converges on e-conomic truth. Settlement never
 * reads these rows — reporting does. Phantom not (yet) imported -> skip silently;
 * the next capture/derive re-syncs. The nightly estimator skips mirrored items
 * (PhantomAttributionService SKIPPED_SELFBILLED, Task 4).
 */
@ApplicationScoped
public class SelfBilledAttributionMirror {

    private static final Logger log = Logger.getLogger(SelfBilledAttributionMirror.class);

    @Inject EntityManager em;

    public record MirrorRow(String consultantUuid, BigDecimal sharePct, BigDecimal attributedAmount) {}
    record PhantomItem(String invoiceUuid, String itemUuid, BigDecimal itemTotal) {}

    /** Max tolerated |voucherNet − Σ shareAmounts| before falling back to proportional mode. */
    private static final BigDecimal DRIFT_TOLERANCE = new BigDecimal("0.01");

    /**
     * One row per assignment, scaled by share-of-voucher-net onto the phantom's OWN
     * item total (sign-safe: no negation juggling — the phantom total carries the sign).
     * The last row absorbs rounding so the rows sum exactly to the phantom total. Pure.
     * <p>
     * PRECONDITION for the absorbing path: the assignments' shareAmounts sum to the
     * voucher net (within {@link #DRIFT_TOLERANCE}). When they drift (e.g. a re-capture
     * added a sibling line and changed the net while assignments were preserved), this
     * method warns and falls back to PROPORTIONAL mode: every row, including the last,
     * is fraction × phantom total with no absorption. The honest under/over-attribution
     * gap is preferable to silently dumping the whole remainder on the last consultant;
     * the warn flags the voucher for the runbook.
     */
    public static List<MirrorRow> computeMirrorRows(BigDecimal phantomItemTotal, BigDecimal voucherNet,
                                                    List<SelfBilledAssignment> assignments) {
        if (voucherNet == null || voucherNet.signum() == 0 || assignments.isEmpty()) return List.of();
        // Group by consultant FIRST: invoice_item_attributions is unique on
        // (invoiceitem_uuid, consultant_uuid), so a voucher split to the SAME consultant
        // across two work periods must collapse to ONE row (MirrorRow has no period
        // component — summing the shares loses nothing). Order is first-seen (deterministic).
        Map<String, BigDecimal> sharesByConsultant = new LinkedHashMap<>();
        for (SelfBilledAssignment a : assignments) {
            sharesByConsultant.merge(a.consultantUuid, a.shareAmount, BigDecimal::add);
        }
        List<Map.Entry<String, BigDecimal>> grouped = new ArrayList<>(sharesByConsultant.entrySet());

        BigDecimal shareSum = grouped.stream()
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal drift = voucherNet.subtract(shareSum);
        boolean proportional = drift.abs().compareTo(DRIFT_TOLERANCE) > 0;
        if (proportional) {
            log.warnf("mirror: assignment shares (sum %s) drift from voucher net %s by %s — "
                            + "falling back to proportional mode (no rounding absorption)",
                    shareSum, voucherNet, drift);
        }
        List<MirrorRow> rows = new ArrayList<>(grouped.size());
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < grouped.size(); i++) {
            Map.Entry<String, BigDecimal> g = grouped.get(i);
            BigDecimal consultantShare = g.getValue();
            BigDecimal fraction = consultantShare.divide(voucherNet, 8, RoundingMode.HALF_UP);
            boolean absorbingLast = !proportional && i == grouped.size() - 1;
            BigDecimal amount = absorbingLast
                    ? phantomItemTotal.subtract(allocated).setScale(2, RoundingMode.HALF_UP)
                    : phantomItemTotal.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
            allocated = allocated.add(amount);
            // On the absorbing row, derive pct from the absorbed amount so pct and amount
            // round-trip consistently (guarded against a zero phantom total).
            BigDecimal sharePct = (absorbingLast && phantomItemTotal.signum() != 0)
                    ? amount.multiply(BigDecimal.valueOf(100)).divide(phantomItemTotal, 2, RoundingMode.HALF_UP)
                    : fraction.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            rows.add(new MirrorRow(g.getKey(), sharePct, amount));
        }
        return rows;
    }

    /**
     * Phantom item for an e-conomic entry within one company, or null when not imported.
     * Entry numbers are only unique PER COMPANY (V338 unique index is
     * (companyuuid, economics_entry_number); the three companies run independent entry
     * sequences), so the company filter is required to avoid mirroring onto another
     * company's phantom. Phantoms carry exactly one invoiceitem today; if more ever
     * appear, warn and use the first (ORDER BY ii.uuid) rather than truncate silently.
     */
    PhantomItem findPhantomItem(long entryNumber, String companyUuid) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT p.uuid, ii.uuid, COALESCE(ii.hours*ii.rate, 0)
                FROM invoices p
                JOIN invoiceitems ii ON ii.invoiceuuid = p.uuid
                WHERE p.type = 'PHANTOM' AND p.economics_entry_number = :e AND p.companyuuid = :c
                ORDER BY ii.uuid
                """).setParameter("e", entryNumber).setParameter("c", companyUuid).getResultList();
        if (rows.isEmpty()) return null;
        if (rows.size() > 1) {
            log.warnf("mirror: entry %d (company %s) has %d phantom invoiceitems; mirroring only the first",
                    entryNumber, companyUuid, rows.size());
        }
        Object[] r = rows.get(0);
        BigDecimal total = (r[2] instanceof BigDecimal b) ? b : BigDecimal.valueOf(((Number) r[2]).doubleValue());
        return new PhantomItem((String) r[0], (String) r[1], total.setScale(2, RoundingMode.HALF_UP));
    }

    /** Replace ALL of the item's attribution rows with the mirror (spec §6.3 "Replace"). */
    void applyMirror(String itemUuid, BigDecimal itemTotal, BigDecimal voucherNet,
                     List<SelfBilledAssignment> assignments) {
        InvoiceItemAttribution.delete("invoiceitemUuid", itemUuid);
        List<MirrorRow> rows = computeMirrorRows(itemTotal, voucherNet, assignments);
        for (MirrorRow r : rows) {
            new InvoiceItemAttribution(itemUuid, r.consultantUuid(), r.sharePct(),
                    r.attributedAmount(), BigDecimal.ZERO, AttributionSource.SELFBILLED_ASSIGNMENT).persist();
        }
        log.debugf("mirror: replaced attributions on item %s with %d SELFBILLED_ASSIGNMENT rows",
                itemUuid, rows.size());
    }

    /** Remove only the mirror rows (assignment deleted -> the estimator may re-estimate). */
    void clearMirror(String itemUuid) {
        InvoiceItemAttribution.delete("invoiceitemUuid = ?1 and source = ?2",
                itemUuid, AttributionSource.SELFBILLED_ASSIGNMENT);
        log.debugf("mirror: cleared SELFBILLED_ASSIGNMENT rows on item %s", itemUuid);
    }

    /**
     * Sync every sibling entry of one voucher. Caller supplies the voucher's lines,
     * its CURRENT assignments (empty list = clear), and the signed voucher net.
     * The phantom lookup is scoped to each line's debtor company (the capture's
     * agreement/debtor company equals the imported phantom's companyuuid).
     * Caller owns the transaction.
     */
    public void syncVoucher(List<SelfBilledLine> siblings, List<SelfBilledAssignment> assignments,
                            BigDecimal voucherNet) {
        for (SelfBilledLine l : siblings) {
            PhantomItem pi = findPhantomItem(l.entryNumber, l.debtorCompanyUuid);
            if (pi == null) {
                log.debugf("mirror: entry %d has no imported phantom yet — skipped", l.entryNumber);
                continue;
            }
            if (assignments.isEmpty()) clearMirror(pi.itemUuid());
            else applyMirror(pi.itemUuid(), pi.itemTotal(), voucherNet, assignments);
        }
    }
}
