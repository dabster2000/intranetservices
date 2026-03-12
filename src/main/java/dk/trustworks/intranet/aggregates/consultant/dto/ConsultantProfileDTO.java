package dk.trustworks.intranet.aggregates.consultant.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.consultant.model.ConsultantProfile;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for consultant profile data. Exposes the cached AI-generated
 * sales pitch, industries, and top skills in a frontend-friendly shape.
 */
@JBossLog
public record ConsultantProfileDTO(
        String useruuid,
        String pitchText,
        List<String> industries,
        List<String> topSkills,
        LocalDateTime generatedAt
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ConsultantProfileDTO fromEntity(ConsultantProfile entity) {
        return new ConsultantProfileDTO(
                entity.getUseruuid(),
                entity.getPitchText(),
                parseJsonArray(entity.getIndustriesJson()),
                parseJsonArray(entity.getTopSkillsJson()),
                entity.getGeneratedAt()
        );
    }

    /**
     * Creates an empty profile DTO for a user whose CV data is missing or
     * whose AI generation failed. The frontend handles null fields gracefully.
     */
    public static ConsultantProfileDTO empty(String useruuid) {
        return new ConsultantProfileDTO(useruuid, null, List.of(), List.of(), null);
    }

    private static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warnf("Failed to parse JSON array: %s", json);
            return List.of();
        }
    }
}
