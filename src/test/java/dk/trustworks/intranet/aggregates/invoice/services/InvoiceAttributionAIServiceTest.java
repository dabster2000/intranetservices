package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.dto.ResolvedAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.dto.ResolvedItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the AI service's static rule validator.
 * No Quarkus, no OpenAI — just exercising validateAgainstRules().
 */
class InvoiceAttributionAIServiceTest {

    private InvoiceAttributionAIService.AnalysisContext contextWith(
        List<InvoiceAttributionAIService.EligibleConsultant> eligible
    ) {
        return new InvoiceAttributionAIService.AnalysisContext(
            List.of(), List.of(), List.of(),
            Map.of(), List.of(), List.of(),
            eligible, Map.of(), Map.of()
        );
    }

    @Test
    void validate_rejectsIneligibleConsultant() {
        var ctx = contextWith(List.of(
            new InvoiceAttributionAIService.EligibleConsultant("alice", "Alice", new BigDecimal("10"))
        ));
        ResolvedItem proposal = new ResolvedItem(
            "L1", "item", new BigDecimal("10"), new BigDecimal("1000"),
            List.of(new ResolvedAttribution("ghost", "Ghost",
                new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("10"))),
            "HIGH", "reason", new BigDecimal("10"), BigDecimal.ZERO
        );
        assertFalse(InvoiceAttributionAIService.validateAgainstRules(proposal, ctx));
    }

    @Test
    void validate_rejectsNonConservingOutput() {
        var ctx = contextWith(List.of(
            new InvoiceAttributionAIService.EligibleConsultant("alice", "Alice", new BigDecimal("10"))
        ));
        // Output hours (5) != line hours (10)
        ResolvedItem proposal = new ResolvedItem(
            "L1", "item", new BigDecimal("10"), new BigDecimal("1000"),
            List.of(new ResolvedAttribution("alice", "Alice",
                new BigDecimal("50"), new BigDecimal("500"), new BigDecimal("5"))),
            "HIGH", "reason", new BigDecimal("10"), BigDecimal.ZERO
        );
        assertFalse(InvoiceAttributionAIService.validateAgainstRules(proposal, ctx));
    }

    @Test
    void validate_acceptsWellFormedOutput() {
        var ctx = contextWith(List.of(
            new InvoiceAttributionAIService.EligibleConsultant("alice", "Alice", new BigDecimal("10")),
            new InvoiceAttributionAIService.EligibleConsultant("bob",   "Bob",   new BigDecimal("0"))
        ));
        ResolvedItem proposal = new ResolvedItem(
            "L1", "item", new BigDecimal("10"), new BigDecimal("1000"),
            List.of(
                new ResolvedAttribution("alice", "Alice", new BigDecimal("60"),
                    new BigDecimal("600"), new BigDecimal("6")),
                new ResolvedAttribution("bob",   "Bob",   new BigDecimal("40"),
                    new BigDecimal("400"), new BigDecimal("4"))
            ),
            "HIGH", "reason", new BigDecimal("10"), new BigDecimal("5")  // positive delta -> split allowed
        );
        assertTrue(InvoiceAttributionAIService.validateAgainstRules(proposal, ctx));
    }

    @Test
    void validate_rejectsMultiAttributionOnUntouchedLine() {
        var ctx = contextWith(List.of(
            new InvoiceAttributionAIService.EligibleConsultant("alice", "Alice", new BigDecimal("10")),
            new InvoiceAttributionAIService.EligibleConsultant("bob",   "Bob",   new BigDecimal("0"))
        ));
        // delta = 0 (untouched), but AI proposed a split — violates Rule 2
        ResolvedItem proposal = new ResolvedItem(
            "L1", "item", new BigDecimal("10"), new BigDecimal("1000"),
            List.of(
                new ResolvedAttribution("alice", "Alice", new BigDecimal("60"),
                    new BigDecimal("600"), new BigDecimal("6")),
                new ResolvedAttribution("bob",   "Bob",   new BigDecimal("40"),
                    new BigDecimal("400"), new BigDecimal("4"))
            ),
            "HIGH", "reason", new BigDecimal("10"), BigDecimal.ZERO
        );
        assertFalse(InvoiceAttributionAIService.validateAgainstRules(proposal, ctx));
    }

    @Test
    void validate_acceptsEmptyAttributionsForLowConfidence() {
        var ctx = contextWith(List.of(
            new InvoiceAttributionAIService.EligibleConsultant("alice", "Alice", new BigDecimal("10"))
        ));
        // Empty attributions with delta=0: sum 0 vs hours 10 -> violates conservation.
        // But for delta <= 0 case, the size check kicks in first: size (0) > 1 is false, so
        // size rule passes. Then conservation fails (0 != 10). Good — validator rejects.
        ResolvedItem proposal = new ResolvedItem(
            "L1", "item", new BigDecimal("10"), new BigDecimal("1000"),
            List.of(),
            "LOW", "unresolvable", new BigDecimal("10"), BigDecimal.ZERO
        );
        // This should FAIL conservation — the user must resolve manually via the wizard
        assertFalse(InvoiceAttributionAIService.validateAgainstRules(proposal, ctx));
    }
}
