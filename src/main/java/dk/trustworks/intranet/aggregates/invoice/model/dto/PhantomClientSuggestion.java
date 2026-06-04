package dk.trustworks.intranet.aggregates.invoice.model.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.SuggestionMethod;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A non-binding client suggestion for a phantom label, shown in the review
 * queue. {@code method == NONE} means no candidate (suggestedClientUuid null).
 */
@RegisterForReflection
public record PhantomClientSuggestion(
        String suggestedClientUuid,
        String suggestedClientName,
        double confidence,
        SuggestionMethod method
) {
    /** Convenience: the "no candidate" suggestion. */
    public static PhantomClientSuggestion none() {
        return new PhantomClientSuggestion(null, null, 0.0, SuggestionMethod.NONE);
    }
}
