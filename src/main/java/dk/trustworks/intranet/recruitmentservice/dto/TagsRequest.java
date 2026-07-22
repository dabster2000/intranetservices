package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body for {@code PUT /recruitment/candidates/{uuid}/tags} — replaces the
 * full tag set (an empty list clears all tags).
 */
public record TagsRequest(
        @NotNull(message = "tags is required — send [] to clear")
        List<@Size(max = 50) String> tags
) {
}
