package dk.trustworks.intranet.recruitmentservice.ports;

import java.util.List;

/**
 * Slice 2 read-only port to the existing {@code cv_tool_employee_cv} table.
 *
 * <p>Used by {@code CANDIDATE_SUMMARY} (and {@code TALENT_POOL_MATCH} in slice 6)
 * to surface comparable Trustworks consultants for prompt context.
 *
 * <p>Implementations: {@link dk.trustworks.intranet.recruitmentservice.infrastructure.CvToolPortImpl}
 * (live, registered with {@code @Alternative @Priority(10)}) and
 * {@link dk.trustworks.intranet.recruitmentservice.infrastructure.NoopCvToolPort}
 * (no-op fallback at {@code @Priority(1)} that throws on call).
 */
public interface CvToolPort {

    /**
     * Existing Trustworks consultants in a given practice; returns up to {@code limit} rows.
     *
     * @param practiceCode the practice enum literal as stored on the {@code consultant.practice}
     *                     column (e.g. {@code "DEV"}, {@code "SA"}, {@code "BA"}, {@code "PM"},
     *                     {@code "CYB"}, {@code "JK"}, {@code "UD"})
     * @param limit        upper bound on rows returned
     */
    List<EmployeeCvSummary> findByPractice(String practiceCode, int limit);

    /**
     * Existing Trustworks consultants at a given career level; returns up to {@code limit} rows.
     *
     * @param careerLevelUuid the row UUID of a {@code user_career_level} record. The matched
     *                        consultants are those whose <em>current</em> {@code career_level}
     *                        equals the {@code career_level} value of the referenced row.
     * @param limit           upper bound on rows returned
     */
    List<EmployeeCvSummary> findByCareerLevelUuid(String careerLevelUuid, int limit);

    /**
     * Compact summary used for AI prompt context.
     *
     * @param userUuid      Trustworks user UUID (FK target on {@code consultant.uuid}/{@code user.uuid})
     * @param displayName   human-readable name from CV Tool ({@code employee_name})
     * @param practice      practice code from {@code consultant.practice} (e.g. {@code "DEV"})
     * @param careerLevel   current career level enum literal from {@code user_career_level} (may be {@code null})
     * @param conceptTokens short skill/topic strings extracted from the CV JSON (may be empty)
     */
    record EmployeeCvSummary(
        String userUuid,
        String displayName,
        String practice,
        String careerLevel,
        List<String> conceptTokens
    ) {}
}
