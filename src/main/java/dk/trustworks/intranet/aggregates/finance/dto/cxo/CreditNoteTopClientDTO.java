package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One row in the "top clients by credit-note volume" table on the
 * GET /clients/cxo/credit-note-rate dashboard, capped at 5 entries.
 *
 * <p>{@code creditNoteCount} is the number of distinct credit-note invoices for this
 * client in the date window — backed by {@code COUNT(DISTINCT i.uuid)} which returns
 * BIGINT in MariaDB, hence {@code long} on the Java side.</p>
 */
public record CreditNoteTopClientDTO(
        String clientName,
        double creditNoteAmountDkk,
        long creditNoteCount
) {}
