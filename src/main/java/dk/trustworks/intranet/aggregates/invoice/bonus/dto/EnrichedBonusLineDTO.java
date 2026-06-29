package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

/**
 * Enriched, display-ready bonus line for the approval UI.
 *
 * <p>Returned for <em>every</em> invoice item of the bonus' invoice (not only persisted lines),
 * so the inline approval panel can render the full proposed allocation even when the bonus has
 * never been edited. When no saved {@code InvoiceBonusLine} exists for an item, {@code percentage}
 * is the smart default ({@code defaultPercentage}); otherwise it is the saved value.</p>
 *
 * <p>Default rule (BASE consultant lines, resolved at invoice date):
 * own production → 0% (OWN), consultant company ≠ recipient company → 80% (CROSS_COMPANY),
 * same company → 100% (SAME_COMPANY). Non-editable lines (CALCULATED / negative-rate / no consultant)
 * carry reason {@code NOT_APPLICABLE}.</p>
 */
public record EnrichedBonusLineDTO(
        String uuid,                 // saved InvoiceBonusLine uuid, or null if not yet persisted
        String invoiceItemUuid,
        String itemName,
        String description,
        String consultantUuid,
        String consultantName,
        String companyUuid,
        String companyAbbreviation,
        double rate,
        double hours,
        double lineAmount,           // round2(rate * hours)
        double percentage,           // current effective % (saved value, else defaultPercentage)
        double defaultPercentage,    // smart default per business rule
        String reason,               // OWN | CROSS_COMPANY | SAME_COMPANY | NOT_APPLICABLE
        double estimatedShare,       // lineAmount * discountFactor * pct/100, sign-flipped for credit notes
        boolean editable,            // false if CALCULATED, negative rate, or no consultant
        String origin                // "BASE" | "CALCULATED"
) {}
