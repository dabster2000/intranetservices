package dk.trustworks.intranet.aggregates.userprofile.dto;

import dk.trustworks.intranet.aggregates.userprofile.model.UserCompetenceTag;
import dk.trustworks.intranet.aggregates.userprofile.model.UserProfileExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Education & profile facts plus competence tags for one person (JK Team 2.0 WP6 §4.6.3).
 * A person without a row still gets a DTO — every field null, tags possibly present.
 */
public record UserProfileExtensionDTO(
        String useruuid,
        String education,
        LocalDate expectedGraduation,
        /** {@code BACHELOR} | {@code KANDIDAT} | null (D12) */
        String studyLevel,
        /** Practice storage code or {@code UD} ("ikke afklaret endnu"); null = never set */
        String primaryDiscipline,
        LocalDateTime updatedAt,
        String updatedBy,
        List<CompetenceTagDTO> tags
) {
    public static UserProfileExtensionDTO of(String useruuid, UserProfileExtension row, List<UserCompetenceTag> tags) {
        List<CompetenceTagDTO> tagDtos = tags == null ? List.of() : tags.stream().map(CompetenceTagDTO::from).toList();
        if (row == null) {
            return new UserProfileExtensionDTO(useruuid, null, null, null, null, null, null, tagDtos);
        }
        return new UserProfileExtensionDTO(
                row.getUseruuid(),
                row.getEducation(),
                row.getExpectedGraduation(),
                row.getStudyLevel(),
                row.getPrimaryDiscipline(),
                row.getUpdatedAt(),
                row.getUpdatedBy(),
                tagDtos);
    }
}
