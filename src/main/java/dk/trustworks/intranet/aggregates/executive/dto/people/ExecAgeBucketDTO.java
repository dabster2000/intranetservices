package dk.trustworks.intranet.aggregates.executive.dto.people;

/**
 * One 5-year age bucket in the current-snapshot age distribution returned by
 * GET /executive/people/age-distribution.
 *
 * <p>Mirrors the {@code ExecAgeBucketDTO} TypeScript contract in
 * {@code src/lib/types/executive.ts}. Source tables are {@code userstatus} and
 * {@code user}; rows correspond to active employees with valid birthday
 * ({@code type IN ('CONSULTANT', 'STUDENT', 'STAFF')}, {@code status='ACTIVE'},
 * {@code birthday IS NOT NULL AND birthday != '0000-00-00'}). Buckets are
 * computed via {@code FLOOR(TIMESTAMPDIFF(YEAR, u.birthday, CURDATE()) / 5) * 5}.</p>
 *
 * <p>{@code bucket} label is derived server-side as
 * {@code "<bucketStart>–<bucketStart+4>"} (en-dash). Counts are non-negative
 * integers from {@code COUNT(DISTINCT useruuid)}; {@code total} equals
 * {@code maleCount + femaleCount + unknownCount}.</p>
 *
 * <p><b>Privacy note:</b> age data is HR-sensitive — class-level
 * {@code dashboard:read} on the resource governs access.</p>
 */
public record ExecAgeBucketDTO(
        String bucket,
        int bucketStart,
        long maleCount,
        long femaleCount,
        long unknownCount,
        long total
) {
    public ExecAgeBucketDTO {
        if (bucket == null || bucket.isBlank())
            throw new IllegalArgumentException("bucket must not be null/blank");
        if (bucketStart < 0 || bucketStart > 200)
            throw new IllegalArgumentException("bucketStart out of range: " + bucketStart);
        if (maleCount < 0)
            throw new IllegalArgumentException("maleCount must be non-negative: " + maleCount);
        if (femaleCount < 0)
            throw new IllegalArgumentException("femaleCount must be non-negative: " + femaleCount);
        if (unknownCount < 0)
            throw new IllegalArgumentException("unknownCount must be non-negative: " + unknownCount);
        if (total < 0)
            throw new IllegalArgumentException("total must be non-negative: " + total);
    }
}
