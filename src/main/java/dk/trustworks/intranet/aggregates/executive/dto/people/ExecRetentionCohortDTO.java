package dk.trustworks.intranet.aggregates.executive.dto.people;

import java.util.List;

/**
 * One hire-year cohort with a survival curve at fixed monthsSinceHire time
 * points, returned by GET /executive/people/retention-cohorts.
 *
 * <p>Mirrors the {@code ExecRetentionCohortDTO} TypeScript contract in
 * {@code src/lib/types/executive.ts}. Each cohort represents employees hired
 * in the given calendar year ({@code 2019}–{@code 2025}); cohort membership
 * is determined by {@code YEAR(MIN(statusdate WHERE status='ACTIVE'))} for
 * employees with {@code type IN ('CONSULTANT', 'STUDENT', 'STAFF')}.</p>
 *
 * <p>{@code points} is always non-null and contains all 9 time points
 * {0, 6, 12, 18, 24, 36, 48, 60, 72} in ascending order, with
 * {@code survivalPct=null} for right-censored points or for empty cohorts.
 * {@code cohortSize} is the count of distinct employees in the cohort.</p>
 */
public record ExecRetentionCohortDTO(
        int cohortYear,
        long cohortSize,
        List<ExecRetentionCohortPointDTO> points
) {
    public ExecRetentionCohortDTO {
        if (cohortYear < 2000 || cohortYear > 2100)
            throw new IllegalArgumentException("cohortYear out of range: " + cohortYear);
        if (cohortSize < 0)
            throw new IllegalArgumentException("cohortSize must be non-negative: " + cohortSize);
        if (points == null)
            throw new IllegalArgumentException("points must not be null");
        // Defensive copy to keep the record immutable
        points = List.copyOf(points);
    }
}
