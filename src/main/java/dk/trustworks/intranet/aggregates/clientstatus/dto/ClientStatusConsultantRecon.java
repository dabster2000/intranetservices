package dk.trustworks.intranet.aggregates.clientstatus.dto;

/**
 * Per-consultant reconciliation for one client-month: registered work vs. invoiced value.
 *
 * <p>The {@code invoicedValue} basis is IDENTICAL to the detail endpoint's per-invoice
 * {@code signedGrossConsultant} basis (same type/status filter, same signs), so
 * {@code Σ invoicedValue} across all rows (incl. the unmatched bucket) equals the detail
 * headline {@code invoiced} within 0.01.
 */
public record ClientStatusConsultantRecon(
        String consultantUuid,      // null for the unmatched-invoice-lines bucket
        String consultantName,      // null for the unmatched bucket; falls back to uuid otherwise
        double registeredHours,
        double registeredValue,
        double invoicedValue,
        double missingValue,        // registeredValue - invoicedValue
        String invoicedSource       // 'ATTRIBUTION' | 'ITEM_CONSULTANT' | 'MIXED' | 'NONE'
) {}
