package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Composition of the pool basis ("Overskud") for a fiscal year (spec §0.1).
 *
 * <pre>estimate = teamRevenue − (totalCosts − excludedSalaries)</pre>
 *
 * {@code poolBasis} is the admin override when present, otherwise the estimate; {@code basisSource}
 * discriminates the two ({@code "OVERRIDE"} vs {@code "ESTIMATE"}).
 */
@Schema(name = "PoolBasisBreakdown", description = "Pool basis (Overskud) composition for a fiscal year")
public record PoolBasisBreakdown(
        double teamRevenue,
        double totalCosts,
        double excludedSalaries,
        double estimate,
        Double override,
        double poolBasis,
        String basisSource
) {
    public static final String SOURCE_ESTIMATE = "ESTIMATE";
    public static final String SOURCE_OVERRIDE = "OVERRIDE";
}
