package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Body for {@code POST /recruitment/candidates/tags/bulk} — union-adds
 * {@code addTags} to every candidate in {@code candidateUuids} (max 200 per
 * call). Existing tags are never removed; unchanged candidates emit no
 * event. Validation is explicit in resource + service ({@code @Valid} is
 * inert in this repo).
 */
public record BulkTagsRequest(
        List<String> candidateUuids,
        List<String> addTags
) {
}
