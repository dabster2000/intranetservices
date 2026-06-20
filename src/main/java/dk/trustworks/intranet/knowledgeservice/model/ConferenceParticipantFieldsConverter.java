package dk.trustworks.intranet.knowledgeservice.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JPA converter for the {@code conference_participants.fields} JSON "bag" column.
 *
 * <p>Deliberately uses a self-contained {@link ObjectMapper} and the JPA
 * {@link AttributeConverter} mechanism rather than {@code @JdbcTypeCode(SqlTypes.JSON)}.
 * The application registers a custom global Jackson {@code ObjectMapperCustomizer}
 * ({@code JavaTimeObjectMapperCustomizer}); when a JSON-typed Hibernate column is mapped
 * with that customizer present, Quarkus refuses to build the SessionFactory at boot
 * ("uses Quarkus' main formatting facilities for JSON columns ... Detected a customized
 * ObjectMapper"). Converting to/from a String here sidesteps Hibernate's JSON
 * FormatMapper entirely, so the persistence unit boots cleanly. The underlying column
 * stays JSON/LONGTEXT.
 */
@Converter
public class ConferenceParticipantFieldsConverter
        implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize conference participant fields bag", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize conference participant fields bag", e);
        }
    }
}
