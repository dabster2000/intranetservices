package dk.trustworks.intranet.competenceservice.content;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * (De)serialises competence payloads with a <strong>module-local</strong> ObjectMapper.
 *
 * <p>This never touches the CDI-managed mapper, and the column it serves is a plain
 * {@code LONGTEXT}. That is not a style choice. The application registers a global
 * {@code JavaTimeObjectMapperCustomizer}; when a JSON-typed Hibernate column is mapped
 * with a customised ObjectMapper present, Quarkus refuses to build the SessionFactory
 * and the container crashes <em>at boot</em>. The failure is runtime-only — {@code mvn
 * package} and the DB-free test tier both pass — and ECS Express then rolls back
 * silently on a deploy that looked green. Converting to and from a String here sidesteps
 * Hibernate's JSON FormatMapper entirely, exactly as
 * {@code ConferenceParticipantFieldsConverter} does.
 *
 * <p>Deserialisation is strict: unknown properties fail. An imported file with a typo'd
 * field should be rejected with a message, not silently stripped of content.
 */
public final class CompetencePayloadCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private CompetencePayloadCodec() {
    }

    public static CompetenceContent.CoursePayload readCourse(String json) {
        return read(json, CompetenceContent.CoursePayload.class, "course");
    }

    public static CompetenceContent.TestPayload readTest(String json) {
        return read(json, CompetenceContent.TestPayload.class, "test");
    }

    public static String write(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise competence payload", e);
        }
    }

    private static <T> T read(String json, Class<T> type, String label) {
        if (json == null || json.isBlank()) {
            throw new WebApplicationException(
                    "Empty " + label + " payload", Response.Status.BAD_REQUEST);
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            // The parser message names the offending field and position, which is what
            // an author importing a hand-edited file needs. It exposes no user data.
            throw new WebApplicationException(
                    "Malformed " + label + " payload: " + rootMessage(e),
                    Response.Status.BAD_REQUEST);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage();
        if (message == null) {
            return t.getClass().getSimpleName();
        }
        int at = message.indexOf(" at [Source");
        return at > 0 ? message.substring(0, at) : message;
    }

    /** The mapper, for the import/export adapter only. Never expose it to Hibernate. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
