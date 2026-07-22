package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA converter for {@code recruitment_positions.stage_set} — an ordered JSON
 * array of stage codes, e.g. {@code ["SCREENING","INTERVIEW_1","OFFER","HIRED"]}.
 * <p>
 * Uses the {@link AttributeConverter}-to-String mechanism rather than
 * {@code @JdbcTypeCode(SqlTypes.JSON)} on a typed field for the same reason as
 * {@code ConferenceParticipantFieldsConverter}: the application's customized
 * global {@code ObjectMapper} makes Quarkus refuse to boot Hibernate's JSON
 * FormatMapper on non-String JSON columns. The entity keeps a typed
 * {@code List<String>}, so responses serialize as a real JSON array.
 */
@Converter
public class StageSetConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize position stage set", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize position stage set", e);
        }
    }
}
