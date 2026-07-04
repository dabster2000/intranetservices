package dk.trustworks.intranet.aggregates.clientstatus;

import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusConsultantRecon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, DB-free per-consultant reconciliation for one client-month (unit-tested).
 *
 * <p>Merges the registered-work side (per consultant: hours + value) with the invoiced side
 * (per invoice item, already signed by invoice type) and attributes each item's value to
 * consultants via the fallback chain:
 * {@code invoice_item_attributions} → {@code invoiceitems.consultantuuid} → unmatched bucket.
 *
 * <p>Invariant: {@code Σ recon.invoicedValue} (including the unmatched bucket row) equals
 * {@code Σ item.signedValue} across all in-scope items — i.e. the detail headline "invoiced".
 */
public final class ClientStatusRecon {

    /** Values below this magnitude are treated as zero (matches ClientStatusMath). */
    static final double EPSILON = 0.01d;

    private ClientStatusRecon() {}

    /** One consultant's registered-work aggregate for the month. */
    public record RegisteredLine(String consultantUuid, double hours, double value) {}

    /** One attribution row for an invoice item: a consultant credited a raw (unsigned) amount. */
    public record AttributionShare(String consultantUuid, double attributedAmount) {}

    /**
     * One in-scope invoice item on the consultant-line basis (the lines counted in
     * {@code signedGrossConsultant}). {@code sign} is +1, or -1 for CREDIT_NOTE.
     * {@code itemValue} is {@code hours*rate} (unsigned); {@code attributions} carries the
     * item's attribution rows (unsigned amounts); {@code consultantUuid} is the fallback
     * {@code invoiceitems.consultantuuid} (may be null/blank).
     */
    public record InvoiceItemLine(double itemValue,
                                  int sign,
                                  String consultantUuid,
                                  List<AttributionShare> attributions) {}

    private static final class Bucket {
        double registeredHours;
        double registeredValue;
        double invoicedValue;
        boolean viaAttribution;
        boolean viaItemConsultant;
    }

    /**
     * Merge registered + invoiced sides into per-consultant reconciliation rows, sorted by
     * {@code missingValue} descending. A trailing unmatched-bucket row (consultantUuid=null)
     * is appended iff its |value| &gt; {@value #EPSILON}.
     *
     * @param registered per-consultant registered work
     * @param items      in-scope invoice items (consultant-line basis)
     * @param nameByUuid consultant uuid → display name (falls back to uuid when absent)
     */
    public static List<ClientStatusConsultantRecon> merge(List<RegisteredLine> registered,
                                                          List<InvoiceItemLine> items,
                                                          Map<String, String> nameByUuid) {
        Map<String, Bucket> byConsultant = new LinkedHashMap<>();
        double unmatched = 0d;

        for (RegisteredLine r : registered) {
            if (r.consultantUuid() == null) continue;
            Bucket b = byConsultant.computeIfAbsent(r.consultantUuid(), k -> new Bucket());
            b.registeredHours += r.hours();
            b.registeredValue += r.value();
        }

        for (InvoiceItemLine item : items) {
            double signedItem = item.itemValue() * item.sign();
            List<AttributionShare> attributions = item.attributions();
            if (attributions != null && !attributions.isEmpty()) {
                double attributedSigned = 0d;
                for (AttributionShare a : attributions) {
                    if (a.consultantUuid() == null) continue;
                    double signed = a.attributedAmount() * item.sign();
                    attributedSigned += signed;
                    Bucket b = byConsultant.computeIfAbsent(a.consultantUuid(), k -> new Bucket());
                    b.invoicedValue += signed;
                    b.viaAttribution = true;
                }
                double residual = signedItem - attributedSigned;
                if (Math.abs(residual) > EPSILON) {
                    unmatched += residual;
                }
            } else if (item.consultantUuid() != null && !item.consultantUuid().isBlank()) {
                Bucket b = byConsultant.computeIfAbsent(item.consultantUuid(), k -> new Bucket());
                b.invoicedValue += signedItem;
                b.viaItemConsultant = true;
            } else {
                unmatched += signedItem;
            }
        }

        List<ClientStatusConsultantRecon> rows = new ArrayList<>(byConsultant.size() + 1);
        for (Map.Entry<String, Bucket> e : byConsultant.entrySet()) {
            String uuid = e.getKey();
            Bucket b = e.getValue();
            String name = nameByUuid != null ? nameByUuid.getOrDefault(uuid, uuid) : uuid;
            rows.add(new ClientStatusConsultantRecon(
                    uuid,
                    name != null ? name : uuid,
                    b.registeredHours,
                    b.registeredValue,
                    b.invoicedValue,
                    b.registeredValue - b.invoicedValue,
                    classifySource(b)));
        }

        if (Math.abs(unmatched) > EPSILON) {
            rows.add(new ClientStatusConsultantRecon(
                    null, null, 0d, 0d, unmatched, -unmatched, "NONE"));
        }

        rows.sort(Comparator.comparingDouble(ClientStatusConsultantRecon::missingValue).reversed());
        return rows;
    }

    private static String classifySource(Bucket b) {
        // Spec B2.4: invoicedSource is NONE whenever invoicedValue nets to zero, regardless of
        // which via-flags were set (e.g. a 0-value consultant line or a same-month credit-note offset).
        if (Math.abs(b.invoicedValue) <= EPSILON) {
            return "NONE";
        }
        if (b.viaAttribution && b.viaItemConsultant) return "MIXED";
        if (b.viaAttribution) return "ATTRIBUTION";
        if (b.viaItemConsultant) return "ITEM_CONSULTANT";
        return "NONE";
    }
}
