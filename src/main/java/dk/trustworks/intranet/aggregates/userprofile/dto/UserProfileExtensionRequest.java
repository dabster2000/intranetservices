package dk.trustworks.intranet.aggregates.userprofile.dto;

import java.time.LocalDate;

/**
 * Body of {@code PUT /users/{useruuid}/profile-extension}. Every field is replaced;
 * null clears. The row is keyed by the path, never by the body.
 */
public record UserProfileExtensionRequest(
        String education,
        LocalDate expectedGraduation,
        String studyLevel,
        String primaryDiscipline
) {
}
