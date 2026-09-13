package dk.trustworks.intranet.aggregates.crm.account.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * One row of the accounts list's CRM columns (CRM spec §4.4): band, supported-by, plan
 * and last activity.
 *
 * <p>Served for every client in one call rather than per row. The list renders several
 * hundred clients and a per-row fetch would be several hundred requests; the underlying
 * queries are four aggregates over the whole table, which is one round trip each.
 */
public record AccountSummaryDTO(
        String clientUuid,
        String band,
        List<PersonDTO> supportedBy,
        /** Null when no plan is expected (Backlog) or none has been started. */
        String planRag,
        LocalDate planUpdatedAt,
        boolean planStarted,
        /** The newest activity of any kind, or null when the account has never been seen. */
        AccountActivityDTO lastActivity) {
}
