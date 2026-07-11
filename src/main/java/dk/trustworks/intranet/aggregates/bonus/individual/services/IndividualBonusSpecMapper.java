package dk.trustworks.intranet.aggregates.bonus.individual.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;

/**
 * (De)serialises the declarative {@link Spec} to/from the JSON text stored in
 * {@code individual_bonus_rule.spec}.
 * <p>
 * Uses its OWN dedicated {@link ObjectMapper} — deliberately not the injected global one and NOT a
 * native JSON column type — because this project's global JavaTimeObjectMapperCustomizer makes
 * Hibernate's {@code @JdbcTypeCode(SqlTypes.JSON)} crash Quarkus boot. Unknown properties are
 * tolerated for forward compatibility (a spec may grow new fields).
 */
@ApplicationScoped
public class IndividualBonusSpecMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public Spec parse(String json) {
        if (json == null || json.isBlank()) {
            throw new BadRequestException("spec must not be empty");
        }
        try {
            return MAPPER.readValue(json, Spec.class);
        } catch (Exception e) {
            throw new BadRequestException("Invalid bonus spec JSON: " + e.getMessage());
        }
    }

    public String serialize(Spec spec) {
        try {
            return MAPPER.writeValueAsString(spec);
        } catch (Exception e) {
            throw new BadRequestException("Could not serialize bonus spec: " + e.getMessage());
        }
    }
}
