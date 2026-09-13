package dk.trustworks.intranet.aggregates.crm.account.dto;

import java.time.LocalDate;

/**
 * One row of the derived account feed (CRM spec §3.2). Nothing here is typed by hand —
 * every row is something a system already saw.
 *
 * <p>Matches {@code IAccountActivity} in {@code src/lib/crm/accountTypes.ts}, minus the
 * {@code isMock} flag that module carried while the rows were invented.
 *
 * @param source   CALENDAR, SLACK, LEAD, CONTRACT, SIGNAL, KYC, BAND or NOTE
 * @param summary  one line, already composed — for a meeting this is built from the
 *                 attendees' names, never from a subject, which is not stored
 * @param refType  what the row is about, so the UI can open the right thing
 * @param refUuid  the id of that thing, or null when there is nothing to open
 * @param actor    the Trustworks person behind it, first name only in the summary
 */
public record AccountActivityDTO(
        String id,
        String source,
        String summary,
        LocalDate occurredAt,
        String actor,
        String refType,
        String refUuid) {
}
