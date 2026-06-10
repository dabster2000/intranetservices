package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/**
 * One QUEUED settlement INTERNAL (Feature 3b) for the workbench's queued lane: a
 * settlement-stamped INTERNAL whose (settlement_year*100 + settlement_month) is in the
 * requested window. {@code total} = Σ items (hours*rate). {@code paid}/{@code outstanding}
 * come from the underlying self-billing vouchers' 8610 'Samlekonto debitorer' remainder at
 * the source's debtor company — paid when every backing voucher's remainder is exactly 0
 * (the client has paid the self-billing invoice); outstanding = Σ positive remainders.
 * Consultant identity is masked without {@code users:read}.
 */
public record QueuedInternalRow(String invoiceUuid, String consultantUuid, String consultantName,
                                int workYear, int workMonth, double total,
                                boolean paid, double outstanding) {}
