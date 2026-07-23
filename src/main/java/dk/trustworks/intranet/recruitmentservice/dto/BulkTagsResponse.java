package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Response for {@code POST /recruitment/candidates/tags/bulk}:
 * {@code updated} counts the candidates whose tag set actually changed
 * (no-ops are excluded — they also emitted no event).
 */
public record BulkTagsResponse(
        int updated
) {
}
