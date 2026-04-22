package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-function generator that projects a source invoice's attribution rows into
 * internal-invoice line items, grouped by issuer company.
 *
 * <p>Rules (spec §5.1, §7):
 * <ul>
 *   <li>One line per (source item × cross-company attribution row).</li>
 *   <li>Issuer = consultant's company as-of source invoicedate; debtor =
 *       source invoice's company. Rows where {@code issuerCompany == sourceCompany}
 *       are skipped (not cross-company).</li>
 *   <li>Rows where {@code company == null} (unresolved) are skipped.</li>
 *   <li>Rows where {@code sharePct == 0} are skipped.</li>
 *   <li>BASE items: {@code rate = source.rate},
 *       {@code hours = HALF_UP(sharePct/100 × source.hours, 2)}.</li>
 *   <li>CALCULATED items: {@code rate = HALF_UP(sharePct/100 × source.rate, 2)},
 *       {@code hours = 1.0}. {@code calculationRef}, {@code ruleId}, {@code label}
 *       are copied from source.</li>
 *   <li>Zero-rounding rows ({@code |rate × hours| < 0.01}) are skipped.</li>
 *   <li>Rounding residual absorbed into the largest-share line within the
 *       source item's per-issuer group; ties broken lexicographically by
 *       {@code attribution.uuid}.</li>
 * </ul>
 *
 * <p>No CDI, no side effects, no persistence — the caller persists the result.
 */
public final class InternalInvoiceLineGenerator {

    private static final BigDecimal ZERO_AMT_THRESHOLD = new BigDecimal("0.01");
    private static final int AMT_SCALE = 2;

    private InternalInvoiceLineGenerator() {
        // pure utility — no instantiation
    }

    /**
     * Generate internal-invoice line items from source items + attributions.
     *
     * @param sourceCompanyUuid the source invoice's issuer company (the "debtor" side
     *                          on the resulting internal invoices — skipped from issuer
     *                          candidates because a company never transfer-prices to itself).
     * @param sourceItems       all items on the source invoice. Never null.
     * @param attributions      all attribution rows linked to {@code sourceItems}.
     *                          Never null. Attributions without a matching source item
     *                          are silently skipped.
     * @param userCompanies     map consultantUuid → companyUuid as-of source invoicedate,
     *                          typically produced by {@code UserCompanyResolver.resolveCompanies}.
     * @return immutable-style map {@code issuerCompanyUuid → lines} in insertion order.
     *         {@link java.util.LinkedHashMap} preserves iteration determinism for tests
     *         and cascading logic. Never null; may be empty.
     */
    public static Map<String, List<InvoiceItem>> generate(
            String sourceCompanyUuid,
            List<InvoiceItem> sourceItems,
            List<InvoiceItemAttribution> attributions,
            Map<String, String> userCompanies) {

        if (sourceItems == null || sourceItems.isEmpty()) {
            return Map.of();
        }
        if (attributions == null || attributions.isEmpty()) {
            return Map.of();
        }

        // Index source items by uuid for O(1) lookup during attribution iteration.
        Map<String, InvoiceItem> sourceByUuid = new LinkedHashMap<>();
        for (InvoiceItem src : sourceItems) {
            sourceByUuid.put(src.uuid, src);
        }

        // Bucket attributions by source item UUID (preserving stable order for determinism).
        Map<String, List<InvoiceItemAttribution>> attrsByItem = new LinkedHashMap<>();
        for (InvoiceItemAttribution attr : attributions) {
            if (attr.invoiceitemUuid == null) continue;
            if (!sourceByUuid.containsKey(attr.invoiceitemUuid)) continue;
            attrsByItem
                    .computeIfAbsent(attr.invoiceitemUuid, k -> new ArrayList<>())
                    .add(attr);
        }

        // Sort each item's attributions deterministically by uuid so iteration order
        // is stable regardless of caller ordering.
        for (List<InvoiceItemAttribution> bucket : attrsByItem.values()) {
            bucket.sort(Comparator.comparing(a -> a.uuid));
        }

        Map<String, List<InvoiceItem>> result = new LinkedHashMap<>();

        // Process each source item and generate lines per (item × cross-company attribution).
        for (InvoiceItem src : sourceItems) {
            List<InvoiceItemAttribution> attrs = attrsByItem.get(src.uuid);
            if (attrs == null || attrs.isEmpty()) continue;
            generateLinesForSourceItem(sourceCompanyUuid, src, attrs, userCompanies, result);
        }

        return result;
    }

    private static void generateLinesForSourceItem(
            String sourceCompanyUuid,
            InvoiceItem src,
            List<InvoiceItemAttribution> attrs,
            Map<String, String> userCompanies,
            Map<String, List<InvoiceItem>> result) {

        // Group by issuer within this source item, so we can absorb rounding residual
        // into the largest-share line per issuer group.
        Map<String, List<PendingLine>> byIssuer = new LinkedHashMap<>();

        for (InvoiceItemAttribution attr : attrs) {
            String consultantCompany = userCompanies.get(attr.consultantUuid);
            if (consultantCompany == null) continue;                        // unresolved
            if (consultantCompany.equals(sourceCompanyUuid)) continue;      // same company — not cross-company
            if (attr.sharePct == null || attr.sharePct.signum() == 0) continue; // zero share

            PendingLine pending = buildPendingLine(src, attr, consultantCompany);
            if (pending == null) continue;

            byIssuer
                    .computeIfAbsent(consultantCompany, k -> new ArrayList<>())
                    .add(pending);
        }

        // Apply rounding-residual absorption per issuer group, then skip tiny lines.
        for (var entry : byIssuer.entrySet()) {
            String issuer = entry.getKey();
            List<PendingLine> group = entry.getValue();
            absorbResidual(src, group);

            for (PendingLine pl : group) {
                // Skip zero-rounding lines AFTER residual absorption so the absorbing line
                // keeps its correction even if the base computed amount was small.
                if (isBelowThreshold(pl.rate, pl.hours)) continue;

                InvoiceItem line = new InvoiceItem();
                line.consultantuuid = pl.attribution.consultantUuid;
                line.itemname = src.itemname;
                line.description = src.description;
                line.rate = pl.rate.doubleValue();
                line.hours = pl.hours.doubleValue();
                line.origin = src.origin;
                line.sourceItemUuid = src.uuid;
                line.sourceAttributionUuid = pl.attribution.uuid;

                if (src.origin == InvoiceItemOrigin.CALCULATED) {
                    line.calculationRef = src.calculationRef;
                    line.ruleId = src.ruleId;
                    line.label = src.label;
                }

                result.computeIfAbsent(issuer, k -> new ArrayList<>()).add(line);
            }
        }
    }

    private static PendingLine buildPendingLine(
            InvoiceItem src,
            InvoiceItemAttribution attr,
            String consultantCompany) {

        BigDecimal sharePct = attr.sharePct;
        if (sharePct.signum() == 0) return null;

        if (src.origin == InvoiceItemOrigin.CALCULATED) {
            // CALCULATED: scale the rate, fix hours=1. Negative rates (discounts) stay negative.
            BigDecimal scaledRate = sharePct
                    .multiply(BigDecimal.valueOf(src.rate))
                    .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);
            return new PendingLine(attr, consultantCompany, scaledRate, BigDecimal.ONE);
        }

        // BASE: keep source rate, scale hours.
        BigDecimal scaledHours = sharePct
                .multiply(BigDecimal.valueOf(src.hours))
                .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);
        return new PendingLine(attr, consultantCompany, BigDecimal.valueOf(src.rate), scaledHours);
    }

    /**
     * Absorb HALF_UP rounding residual into the largest-share pending line so the group's
     * sum matches the full attributed amount from the source. BASE absorbs into hours;
     * CALCULATED absorbs into rate (hours is fixed at 1). Ties broken lexicographically
     * by {@code attribution.uuid} for deterministic behavior.
     */
    private static void absorbResidual(InvoiceItem src, List<PendingLine> group) {
        if (group.isEmpty()) return;

        BigDecimal groupSharePct = BigDecimal.ZERO;
        for (PendingLine pl : group) {
            groupSharePct = groupSharePct.add(pl.attribution.sharePct);
        }
        if (groupSharePct.signum() == 0) return;

        if (src.origin == InvoiceItemOrigin.CALCULATED) {
            // Expected group rate-sum = groupSharePct/100 × source.rate (HALF_UP 2 dp).
            BigDecimal expectedTotalRate = groupSharePct
                    .multiply(BigDecimal.valueOf(src.rate))
                    .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);
            BigDecimal actualTotalRate = BigDecimal.ZERO;
            for (PendingLine pl : group) {
                actualTotalRate = actualTotalRate.add(pl.rate);
            }
            BigDecimal residual = expectedTotalRate.subtract(actualTotalRate);
            if (residual.signum() != 0) {
                PendingLine target = pickAbsorbingLine(group);
                target.rate = target.rate.add(residual).setScale(AMT_SCALE, RoundingMode.HALF_UP);
            }
        } else {
            // BASE: Expected group hours-sum = groupSharePct/100 × source.hours.
            BigDecimal expectedTotalHours = groupSharePct
                    .multiply(BigDecimal.valueOf(src.hours))
                    .divide(BigDecimal.valueOf(100), AMT_SCALE, RoundingMode.HALF_UP);
            BigDecimal actualTotalHours = BigDecimal.ZERO;
            for (PendingLine pl : group) {
                actualTotalHours = actualTotalHours.add(pl.hours);
            }
            BigDecimal residual = expectedTotalHours.subtract(actualTotalHours);
            if (residual.signum() != 0) {
                PendingLine target = pickAbsorbingLine(group);
                target.hours = target.hours.add(residual).setScale(AMT_SCALE, RoundingMode.HALF_UP);
            }
        }
    }

    /**
     * Pick the line with the largest share to absorb rounding residual. Ties broken
     * lexicographically by attribution UUID — deterministic and documented in spec §5.1.
     */
    private static PendingLine pickAbsorbingLine(List<PendingLine> group) {
        PendingLine best = group.get(0);
        for (int i = 1; i < group.size(); i++) {
            PendingLine cand = group.get(i);
            int cmp = cand.attribution.sharePct.compareTo(best.attribution.sharePct);
            if (cmp > 0) {
                best = cand;
            } else if (cmp == 0 && cand.attribution.uuid.compareTo(best.attribution.uuid) < 0) {
                // lexicographically smaller UUID wins the tie — deterministic
                best = cand;
            }
        }
        return best;
    }

    private static boolean isBelowThreshold(BigDecimal rate, BigDecimal hours) {
        BigDecimal amount = rate.multiply(hours).abs();
        return amount.compareTo(ZERO_AMT_THRESHOLD) < 0;
    }

    /**
     * Working tuple for per-issuer residual absorption before constructing the final
     * {@link InvoiceItem}. Fields are mutable because residual absorption updates them
     * in place; {@link InvoiceItem} is only built once absorption settles.
     */
    private static final class PendingLine {
        final InvoiceItemAttribution attribution;
        final String issuerCompanyUuid;
        BigDecimal rate;
        BigDecimal hours;

        PendingLine(InvoiceItemAttribution attribution, String issuerCompanyUuid,
                    BigDecimal rate, BigDecimal hours) {
            this.attribution = attribution;
            this.issuerCompanyUuid = issuerCompanyUuid;
            this.rate = rate;
            this.hours = hours;
        }
    }
}
