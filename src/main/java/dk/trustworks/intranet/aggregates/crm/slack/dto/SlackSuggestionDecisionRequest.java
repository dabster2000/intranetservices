package dk.trustworks.intranet.aggregates.crm.slack.dto;

/**
 * What somebody decided about a company name nobody claims (spec §6.2).
 *
 * <p>The calendar lane's request shape without a domain — there is nothing to attach here,
 * because the alias IS the row. Three decisions, and they are not variations of one thing:
 *
 * <ul>
 *   <li>{@code ADD} — create a company for it. {@code name} is required; {@code segment}
 *       and {@code ownerUuid} are optional and behave exactly as the Add-company dialog's.
 *       The row is created as a PROSPECT and the hint is then LINKED to it, so from the
 *       next run the name resolves to the account instead of coming back as a hint.</li>
 *   <li>{@code LINK} — it is a client Intra already has. {@code clientUuid} is required.
 *       Nothing is written on the client: linking the hint is itself the alias the matcher
 *       carries, which is why no {@code client_domain} equivalent appears here.</li>
 *   <li>{@code IGNORE} — never suggest it again. A per-name deny-list, not a deletion: the
 *       sightings keep accruing quietly, so the decision is reversible and auditable.</li>
 * </ul>
 *
 * @param decision  ADD · LINK · IGNORE
 * @param name      ADD only: the company name, pre-filled from what was heard and editable
 * @param segment   ADD only: a {@code ClientSegment} name; OTHER when absent or unknown
 * @param ownerUuid ADD only: an optional owner
 * @param startPursuing ADD only: set the band to ACTIVE, which requires an owner
 * @param clientUuid LINK only: the client the name belongs to
 */
public record SlackSuggestionDecisionRequest(
        String decision,
        String name,
        String segment,
        String ownerUuid,
        boolean startPursuing,
        String clientUuid) {
}
