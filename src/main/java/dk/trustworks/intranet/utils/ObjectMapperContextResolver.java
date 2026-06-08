package dk.trustworks.intranet.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;

/**
 * Exposes the CDI-managed {@link ObjectMapper} — already configured with the JavaTimeModule by
 * {@link JavaTimeObjectMapperCustomizer} — to JAX-RS response serialization via a
 * {@link ContextResolver}.
 *
 * <p>Quarkus REST resolves the response writer's {@code ObjectMapper} differently for a resource
 * method that returns a typed entity (e.g. {@code Client}) versus one that returns an untyped
 * {@code Response.entity(...)}. The typed path already picked up the customized mapper, but the
 * {@code Response.entity(Client)} path (e.g. {@code POST /clients}) resolved a mapper WITHOUT the
 * JavaTimeModule and failed to serialize {@code Client.created} (a {@code LocalDateTime}) with an
 * {@code InvalidDefinitionException} — surfacing as a 500 via {@code NativeInvalidDefinitionExceptionMapper}.
 *
 * <p>Registering this resolver makes every serialization path use the same jsr310-enabled mapper,
 * so dates serialize as ISO-8601 strings consistently regardless of the return type.
 */
@Provider
public class ObjectMapperContextResolver implements ContextResolver<ObjectMapper> {

    @Inject
    ObjectMapper objectMapper;

    @Override
    public ObjectMapper getContext(Class<?> type) {
        return objectMapper;
    }
}
