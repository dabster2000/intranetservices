package dk.trustworks.intranet.aggregates.invoice.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegisteredDeliveryEvidenceResolverTest {

    private final RegisteredDeliveryEvidenceResolver resolver = new RegisteredDeliveryEvidenceResolver();

    @Test
    void collapsesByteIdenticalJoinDuplicates() {
        var row = row("w1", "u2", "7.5000004", "1000.0000004");
        var resolved = resolver.resolveRows(List.of(row, row));
        assertEquals(1, resolved.size());
        assertEquals(new BigDecimal("7500.000000000000"), resolved.getFirst().deliveryValue());
        assertEquals(RegisteredDeliveryEvidenceResolver.RateResolutionStatus.RESOLVED,
                resolved.getFirst().rateResolutionStatus());
    }

    @Test
    void preservesWorkAsAndUniquelyResolvedZero() {
        var resolved = resolver.resolveRows(List.of(row("w1", "work-as-user", "-1.000000", "0"))).getFirst();
        assertEquals("work-as-user", resolved.effectiveConsultantUuid());
        assertEquals(new BigDecimal("0.000000000000"), resolved.deliveryValue());
        assertEquals(RegisteredDeliveryEvidenceResolver.RateResolutionStatus.RESOLVED,
                resolved.rateResolutionStatus());
    }

    @Test
    void rejectsAmbiguousOrNegativeRates() {
        var ambiguous = resolver.resolveRows(List.of(
                row("w1", "u1", "1", "1000"), row("w1", "u1", "1", "1200"))).getFirst();
        assertEquals(RegisteredDeliveryEvidenceResolver.RateResolutionStatus.AMBIGUOUS,
                ambiguous.rateResolutionStatus());

        var invalid = resolver.resolveRows(List.of(row("w2", "u1", "1", "-1"))).getFirst();
        assertEquals(RegisteredDeliveryEvidenceResolver.RateResolutionStatus.INVALID,
                invalid.rateResolutionStatus());
    }

    @Test
    void doesNotCollapseDistinctRateRelationshipsWithTheSameValue() {
        var first = new RegisteredDeliveryEvidenceResolver.RawDeliveryRow(
                "w1", "registrant", "u1", LocalDate.of(2026, 1, 2),
                "task", "project", "contract", "cp-1", "cc-1", "1", "1000");
        var second = new RegisteredDeliveryEvidenceResolver.RawDeliveryRow(
                "w1", "registrant", "u1", LocalDate.of(2026, 1, 2),
                "task", "project", "contract", "cp-1", "cc-2", "1", "1000");

        var resolved = resolver.resolveRows(List.of(first, second)).getFirst();

        assertEquals(RegisteredDeliveryEvidenceResolver.RateResolutionStatus.AMBIGUOUS,
                resolved.rateResolutionStatus());
    }

    private static RegisteredDeliveryEvidenceResolver.RawDeliveryRow row(
            String workUuid, String effectiveUser, String duration, String rate) {
        return new RegisteredDeliveryEvidenceResolver.RawDeliveryRow(
                workUuid, "registrant", effectiveUser, LocalDate.of(2026, 1, 2),
                "task", "project", "contract", duration, rate);
    }
}
