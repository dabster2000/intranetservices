package dk.trustworks.intranet.aggregates.invoice.model.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;

/**
 * One row of the phantom attribution review queue, grouped by clientname label:
 * the unattributed in-scope phantoms for that label, their current mapping
 * state, and (when unmapped) an auto-suggestion to confirm.
 */
@RegisterForReflection
public record PhantomAttributionReviewDTO(
        String clientname,
        long phantomCount,
        BigDecimal totalAmount,
        String mappedClientUuid,
        String mappedClientName,
        boolean excluded,
        PhantomClientSuggestion suggestion
) {}
