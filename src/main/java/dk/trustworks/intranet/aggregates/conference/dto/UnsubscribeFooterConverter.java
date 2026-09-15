package dk.trustworks.intranet.aggregates.conference.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class UnsubscribeFooterConverter implements AttributeConverter<UnsubscribeFooter, String> {
    private static final ObjectMapper JSON = new ObjectMapper();
    public String convertToDatabaseColumn(UnsubscribeFooter value) {
        try { return value == null ? null : JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid footer descriptor"); }
    }
    public UnsubscribeFooter convertToEntityAttribute(String value) {
        try { return value == null ? null : JSON.readValue(value, UnsubscribeFooter.class); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid saved footer descriptor"); }
    }
}
