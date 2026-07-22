package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA converter for {@code recruitment_positions.scorecard_template} — a JSON
 * array of {@link ScorecardAttribute} entries. Same String-converter rationale
 * as {@link StageSetConverter}.
 */
@Converter
public class ScorecardTemplateConverter implements AttributeConverter<List<ScorecardAttribute>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ScorecardAttribute>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<ScorecardAttribute> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize position scorecard template", e);
        }
    }

    @Override
    public List<ScorecardAttribute> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize position scorecard template", e);
        }
    }
}
