package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /recruitment/candidates/dedupe-check} (plan §P3).
 * At least one of the two identifiers must be present (400 otherwise).
 * POST rather than GET so the email never lands in URLs or access logs.
 */
public record DedupeCheckRequest(
        @Size(max = 255) String email,
        @Size(max = 500) String linkedinUrl
) {
}
