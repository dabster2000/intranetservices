package dk.trustworks.intranet.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

// Auto-registration of JavaTimeModule by quarkus-rest-jackson is suppressed by the
// presence of quarkus-resteasy-client-jackson (classic stack) on the classpath.
// Without this customizer, serializing entities with LocalDateTime fields (e.g. Client.created)
// fails with InvalidDefinitionException and produces a malformed response body.
@Singleton
public class JavaTimeObjectMapperCustomizer implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
