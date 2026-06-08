package dk.trustworks.intranet.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import dk.trustworks.intranet.dao.crm.model.Client;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces and guards the {@code POST /clients} 500 (InvalidDefinitionException on
 * {@code Client.created}): a plain {@link ObjectMapper} cannot serialize a {@code LocalDateTime},
 * the JavaTimeModule registered by {@link JavaTimeObjectMapperCustomizer} fixes it, and
 * {@link ObjectMapperContextResolver} hands that mapper to JAX-RS response serialization.
 */
class ObjectMapperContextResolverTest {

    private static Client clientWithCreated() {
        Client c = new Client();
        c.setUuid("c-1");
        c.setName("Acme A/S");
        c.setCreated(LocalDateTime.of(2026, 6, 8, 9, 23, 25));
        return c;
    }

    @Test
    void plain_mapper_fails_on_localDateTime_created() {
        ObjectMapper plain = new ObjectMapper();
        assertThrows(InvalidDefinitionException.class,
                () -> plain.writeValueAsString(clientWithCreated()));
    }

    @Test
    void customized_mapper_serializes_created_as_iso_string() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        new JavaTimeObjectMapperCustomizer().customize(mapper);

        String json = mapper.writeValueAsString(clientWithCreated());

        assertTrue(json.contains("\"created\":\"2026-06-08T09:23:25\""),
                "created should serialize as an ISO-8601 string, was: " + json);
    }

    @Test
    void resolver_returns_the_injected_mapper() {
        ObjectMapper mapper = new ObjectMapper();
        new JavaTimeObjectMapperCustomizer().customize(mapper);
        ObjectMapperContextResolver resolver = new ObjectMapperContextResolver();
        resolver.objectMapper = mapper;

        assertSame(mapper, resolver.getContext(Client.class));
    }
}
