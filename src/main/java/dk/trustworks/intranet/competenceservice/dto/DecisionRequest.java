package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.competenceservice.model.DecisionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The body of {@code POST /admin/attempts/decisions} (spec §6.2).
 *
 * <p><strong>Explicit attempt uuids only.</strong> There is deliberately no "approve
 * everything pending" wildcard (§5.10): bulk approve must approve exactly the rows the leader
 * was looking at, so what was attested is what was seen. A wildcard would let an attempt that
 * arrived between rendering the queue and clicking the button be approved by somebody who
 * never saw it — which is precisely the signature an auditor looks for when they doubt an
 * approval trail.
 *
 * <p>The actor is never in this body. It is {@code RequestHeaderHolder.getUserUuid()}, which
 * is what makes the self-approval refusal unforgeable, and decisions are refused outright
 * while impersonating (§10.4).
 *
 * <p>Nothing here is normalised into a default. A missing decision is a {@code 400} from the
 * service, not an assumed {@code APPROVED} — guessing on a write that becomes evidence about a
 * colleague is not a kindness.
 *
 * @param attemptUuids the rows to decide, in the order the caller sent them. Duplicates are
 *                     decided once by the service, so a double-clicked bulk button cannot
 *                     append the same verdict to the append-only ledger twice.
 * @param decision     {@code APPROVED} or {@code REVOKED}; deserialised from the constant name
 * @param note         free text, ≤ 1000 chars. Required for a {@code REVOKED} decision —
 *                     taking something away from a person has to say why — and optional for an
 *                     approval, which may stand on the score. Never logged: it is free text
 *                     about a named colleague.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionRequest(List<String> attemptUuids, DecisionType decision, String note) {

    public DecisionRequest {
        // Tolerates null entries — a null uuid in the array earns a per-item "No such
        // attempt", which is a better answer than a 500 out of List.copyOf.
        attemptUuids = attemptUuids == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(attemptUuids));
    }
}
