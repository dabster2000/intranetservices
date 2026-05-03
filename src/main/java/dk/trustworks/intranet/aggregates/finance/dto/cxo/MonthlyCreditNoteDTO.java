package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One month in the credit-note-rate series for GET /clients/cxo/credit-note-rate.
 *
 * <p>{@code monthKey} is the {@code YYYYMM} key produced by {@code DATE_FORMAT(invoicedate, '%Y%m')}.
 * {@code creditNoteRatePct} is the percentage of total billed value that was credited
 * back, computed as {@code creditNoteAmountDkk / (invoiceAmountDkk + creditNoteAmountDkk) * 100}
 * rounded to two decimals. When the denominator is zero the BFF returns {@code 0.0}, so this
 * field is a primitive {@code double} (never null).</p>
 */
public record MonthlyCreditNoteDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        double invoiceAmountDkk,
        double creditNoteAmountDkk,
        double creditNoteRatePct
) {}
