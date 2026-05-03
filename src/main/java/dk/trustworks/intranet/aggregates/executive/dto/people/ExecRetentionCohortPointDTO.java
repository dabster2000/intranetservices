package dk.trustworks.intranet.aggregates.executive.dto.people;

/**
 * One time-point on a retention cohort survival curve, returned as part of
 * {@link ExecRetentionCohortDTO} from GET /executive/people/retention-cohorts.
 *
 * <p>Mirrors the {@code ExecRetentionCohortPointDTO} TypeScript contract in
 * {@code src/lib/types/executive.ts}. {@code monthsSinceHire} is one of the
 * fixed time points {0, 6, 12, 18, 24, 36, 48, 60, 72}. {@code survivalPct}
 * is computed server-side as
 * {@code round((survived / cohortSize) * 10000) / 100}; {@code null} for
 * right-censored time points (i.e. when {@code monthsSinceHire} exceeds the
 * months elapsed since the cohort year's Jan 1 reference) or for empty
 * cohorts.</p>
 */
public record ExecRetentionCohortPointDTO(
        int monthsSinceHire,
        Double survivalPct
) {
    public ExecRetentionCohortPointDTO {
        if (monthsSinceHire < 0)
            throw new IllegalArgumentException("monthsSinceHire must be non-negative: " + monthsSinceHire);
        if (survivalPct != null && !Double.isFinite(survivalPct))
            throw new IllegalArgumentException("survivalPct must be finite or null: " + survivalPct);
        if (survivalPct != null && (survivalPct < 0.0 || survivalPct > 100.0))
            throw new IllegalArgumentException("survivalPct out of [0, 100]: " + survivalPct);
    }
}
