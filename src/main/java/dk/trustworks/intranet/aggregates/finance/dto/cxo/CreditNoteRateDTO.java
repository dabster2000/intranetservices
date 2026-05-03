package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * Wrapper response for GET /clients/cxo/credit-note-rate.
 *
 * <p>{@code monthly} is the time-ordered (ascending {@code monthKey}) series of
 * monthly invoice vs credit-note volumes plus the derived rate. {@code topClients}
 * is the top-5 clients by absolute credit-note amount in the same window.</p>
 */
public record CreditNoteRateDTO(
        List<MonthlyCreditNoteDTO> monthly,
        List<CreditNoteTopClientDTO> topClients
) {
    public CreditNoteRateDTO {
        Objects.requireNonNull(monthly, "monthly");
        Objects.requireNonNull(topClients, "topClients");
    }
}
