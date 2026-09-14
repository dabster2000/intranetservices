package dk.trustworks.intranet.aggregates.crm.account.dto;

import java.time.LocalDate;

/**
 * The derived history of one account (spec §2.1) — the same shape on the list row and on
 * the account page, so the chip reads identically on both.
 *
 * <p>Matches {@code IAccountRelationship} in {@code src/lib/crm/accountTypes.ts} field for
 * field.
 *
 * @param relationship        CUSTOMER · FORMER · PROSPECT · CONTACT
 * @param customerSince       earliest assignment start; null unless we have ever worked here
 * @param lastWorked          latest assignment end; null unless we have ever worked here.
 *                            Rendered as "Mar 2024" — the month is the honest resolution
 * @param contractCount       how many contracts this client has ever had, any status
 * @param winBack             a FORMER customer with an open lead or a band above Backlog
 * @param expiringWithin90d   a CUSTOMER whose every running assignment ends within 90 days
 * @param openLeads           open leads (not WON, not LOST) on this client
 */
public record AccountRelationshipDTO(
        String relationship,
        LocalDate customerSince,
        LocalDate lastWorked,
        int contractCount,
        boolean winBack,
        boolean expiringWithin90d,
        int openLeads) {
}
