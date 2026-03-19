package dk.trustworks.intranet.security.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A single REST endpoint with its security annotations,
 * as discovered by scanning JAX-RS resource classes at startup.
 */
public record EndpointRegistryEntry(
        String method,
        String path,
        @JsonProperty("rolesAllowed") List<String> rolesAllowed,
        @JsonProperty("permitAll") boolean permitAll,
        String domain
) {}
