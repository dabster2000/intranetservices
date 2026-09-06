package dk.trustworks.intranet.aggregates.finance.dto;

import java.util.List;

/**
 * Pricing-model distribution with drill-down (JK Team 2.0 WP5 §4.5 / WP6 Staffing, hourly kind):
 * distinct junior members with an active consultant line per model in the period. A junior
 * with lines under two models counts in both; a junior with an active line carrying no model
 * lands in the {@code NONE} bucket.
 */
public record TeamPricingModelDistributionDTO(
        List<ModelBucket> models,
        int juniorsWithLines,
        int juniorsWithoutLines,
        List<NameRef> withoutLines
) {
    public record ModelBucket(
            /** pricing_model_definitions.code, or {@code NONE} */
            String code,
            String name,
            int juniorCount,
            List<NameRef> juniors
    ) {}
}
