package dk.trustworks.intranet.aggregates.crm.calendar.dto;

/**
 * What somebody decided about a domain nobody claims (spec §2.5).
 *
 * <p>Three decisions, and they are not variations of one thing:
 *
 * <ul>
 *   <li>{@code ADD} — create a company for it. {@code name} is required; {@code segment}
 *       and {@code ownerUuid} are optional and behave exactly as the Add-company dialog's.
 *       The row is created as a PROSPECT with the domain attached, so its meetings land on
 *       the account from the next sync.</li>
 *   <li>{@code LINK} — it belongs to a client Intra already has. {@code clientUuid} is
 *       required; the domain is added to that client, which is what makes its meeting
 *       history start appearing there.</li>
 *   <li>{@code IGNORE} — never suggest it again. A per-domain deny-list, not a deletion:
 *       the sightings stay, so the decision is reversible and auditable.</li>
 * </ul>
 *
 * @param decision  ADD · LINK · IGNORE
 * @param name      ADD only: the company name
 * @param segment   ADD only: a {@code ClientSegment} name; OTHER when absent or unknown
 * @param ownerUuid ADD only: an optional owner
 * @param startPursuing ADD only: set the band to ACTIVE, which requires an owner
 * @param clientUuid LINK only: the client the domain belongs to
 */
public record CalendarSuggestionDecisionRequest(
        String decision,
        String name,
        String segment,
        String ownerUuid,
        boolean startPursuing,
        String clientUuid) {
}
