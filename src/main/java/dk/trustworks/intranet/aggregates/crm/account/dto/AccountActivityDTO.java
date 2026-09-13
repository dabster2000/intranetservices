package dk.trustworks.intranet.aggregates.crm.account.dto;

import java.time.LocalDate;

/**
 * One row of the derived account feed (CRM spec §3.2). Every row but one is something a
 * system already saw; a {@code NOTE} row is the one line somebody typed, and its
 * {@code summary} is that line verbatim.
 *
 * <p>Matches {@code IAccountActivity} in {@code src/lib/crm/accountTypes.ts}, minus the
 * {@code isMock} flag that module carried while the rows were invented.
 *
 * @param source   CALENDAR, SLACK, LEAD, CONTRACT, SIGNAL, KYC, BAND or NOTE
 * @param summary  one line, already composed — for a meeting this is built from the
 *                 attendees' names, never from a subject, which is not stored; for a note
 *                 it is the note itself, which makes a note the only row in this feed that
 *                 carries free text about a third party
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
