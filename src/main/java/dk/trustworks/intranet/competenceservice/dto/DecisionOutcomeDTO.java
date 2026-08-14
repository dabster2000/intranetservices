package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The per-item verdict of a bulk decision (spec §6.2).
 *
 * <p>Shaped after {@code CompetenceDecisionService.DecisionOutcome} and deliberately a
 * separate type: the service record is an internal result, and letting a service type be the
 * wire contract is how a later field — an actor uuid, an internal reason code — becomes a
 * response body nobody decided to publish. The mapping is three fields; the resource does it.
 *
 * <p>Bulk approve is partial success by design. One self-approval or one row that drifted out
 * of reach between rendering and clicking must not discard the rest of the batch, so every
 * item reports for itself and the HTTP status stays {@code 200} (§6.2).
 *
 * @param attemptUuid the row this verdict is about, echoed back so the client can mark it
 * @param ok          whether the decision was written to the ledger
 * @param message     why not, when {@code ok} is false — "You cannot approve your own
 *                    attempt", "Outside your data scope", "A revocation requires a note".
 *                    Null on success, and omitted from the JSON by {@code NON_NULL}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionOutcomeDTO(String attemptUuid, boolean ok, String message) {
}
