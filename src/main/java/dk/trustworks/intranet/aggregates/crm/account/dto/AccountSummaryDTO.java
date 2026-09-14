package dk.trustworks.intranet.aggregates.crm.account.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * One row of the accounts list's CRM columns (CRM spec §4.4): relationship, band,
 * supported-by, the account team, plan and last activity.
 *
 * <p>Served for every client in one call rather than per row. The list renders several
 * hundred clients and a per-row fetch would be several hundred requests; the underlying
 * queries are a handful of aggregates over the whole table, which is one round trip each.
 *
 * @param relationship  the derived history — customer / former / prospect / contact (spec §2.1)
 * @param knownBy       how many colleagues have a relationship-graph edge to somebody at
 *                      this company: a meeting, a signal, or a TrustLink connection. It is
 *                      the Contacts view's "who knows them" column, and the only number on
 *                      a contact row that is worth anything.
 * @param signals       how many signals have been filed on it — the Contacts view's "Heard"
 * @param addedBy       who created the row, from its CREATED activity-log entry; null for
 *                      rows that predate the log or were created by a job
 */
public record AccountSummaryDTO(
        String clientUuid,
        String band,
        AccountRelationshipDTO relationship,
        List<PersonDTO> supportedBy,
        List<PersonDTO> members,
        int knownBy,
        int signals,
        PersonDTO addedBy,
        /** Null when no plan is expected (Backlog) or none has been started. */
        String planRag,
        LocalDate planUpdatedAt,
        boolean planStarted,
        /** The newest activity of any kind, or null when the account has never been seen. */
        AccountActivityDTO lastActivity) {
}
