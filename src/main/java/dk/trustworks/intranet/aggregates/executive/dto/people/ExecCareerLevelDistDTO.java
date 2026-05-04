package dk.trustworks.intranet.aggregates.executive.dto.people;

/**
 * One career-level entry in the current-snapshot career-level distribution
 * returned by GET /executive/people/career-level-distribution.
 *
 * <p>Mirrors the {@code ExecCareerLevelDistDTO} TypeScript contract in
 * {@code src/lib/types/executive.ts}. Source tables are {@code user_career_level}
 * and {@code userstatus}; rows correspond to active consultants
 * ({@code us.type='CONSULTANT', us.status='ACTIVE'}) joined to their most-recent
 * {@code user_career_level} row.</p>
 *
 * <p>The endpoint always returns all 18 career levels (in canonical order)
 * with {@code count = 0} for levels with no active consultants — matches BFF
 * semantics. {@code careerTrack} is a hardcoded grouping (see
 * {@code CAREER_LEVEL_TRACK_MAP} in the service).</p>
 */
public record ExecCareerLevelDistDTO(
        String careerLevel,
        String careerTrack,
        long count
) {
    public ExecCareerLevelDistDTO {
        if (careerLevel == null || careerLevel.isBlank())
            throw new IllegalArgumentException("careerLevel must not be null/blank");
        if (careerTrack == null || careerTrack.isBlank())
            throw new IllegalArgumentException("careerTrack must not be null/blank");
        if (count < 0)
            throw new IllegalArgumentException("count must be non-negative: " + count);
    }
}
