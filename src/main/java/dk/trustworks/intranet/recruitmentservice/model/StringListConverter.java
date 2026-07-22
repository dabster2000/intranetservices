package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA converter for plain string-array JSON columns on the candidate
 * aggregate ({@code recruitment_candidates.tags} and
 * {@code .specializations}).
 * <p>
 * Same {@link AttributeConverter}-to-String mechanism as
 * {@link StageSetConverter} (and for the same reason: the application's
 * customized global {@code ObjectMapper} makes Quarkus refuse to boot
 * Hibernate's JSON FormatMapper on non-String JSON columns). Kept separate
 * from {@code StageSetConverter} because that one's contract is the ordered
 * stage vocabulary — this one is free-form strings.
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

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
            throw new IllegalArgumentException("Failed to serialize string list", e);
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
            throw new IllegalArgumentException("Failed to deserialize string list", e);
        }
    }
}
