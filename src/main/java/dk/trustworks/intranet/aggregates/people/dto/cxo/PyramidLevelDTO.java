package dk.trustworks.intranet.aggregates.people.dto.cxo;

import java.util.List;

/**
 * One pyramid bucket row in {@link ConsultantPyramidDTO} for the consultant
 * pyramid distribution returned by GET /people/cxo/consultant-pyramid.
 *
 * <p>Mirrors the {@code PyramidLevelDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. Bucket labels and target percents are
 * hardcoded server-side and align with the {@code PYRAMID_TARGETS}
 * constant in the TypeScript file.</p>
 *
 * <p>{@code careerLevels} enumerates the {@code user_career_level.career_level}
 * codes that map to this bucket (e.g. {@code Junior} =
 * {@code [JUNIOR_CONSULTANT, PROFESSIONAL_CONSULTANT]}). {@code actualPercent}
 * is rounded to 2 decimals (count / total * 10000 / 100); when total = 0 the
 * percent is 0 (matches BFF semantics).</p>
 */
public record PyramidLevelDTO(
        String bucketLabel,
        List<String> careerLevels,
        long actualCount,
        double actualPercent,
        double targetPercent
) {
    public PyramidLevelDTO {
        if (bucketLabel == null) throw new IllegalArgumentException("bucketLabel must not be null");
        if (careerLevels == null) throw new IllegalArgumentException("careerLevels must not be null");
        if (actualCount < 0) throw new IllegalArgumentException("actualCount must be non-negative: " + actualCount);
        if (!Double.isFinite(actualPercent))
            throw new IllegalArgumentException("actualPercent must be finite: " + actualPercent);
        if (!Double.isFinite(targetPercent))
            throw new IllegalArgumentException("targetPercent must be finite: " + targetPercent);
        // Defensive copy for immutability.
        careerLevels = List.copyOf(careerLevels);
    }
}
