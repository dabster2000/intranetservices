package dk.trustworks.intranet.aggregates.executive.resources;

import dk.trustworks.intranet.aggregates.executive.services.ExecutivePeopleService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashSet;
import java.util.Set;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST API for the Executive Dashboard people-domain endpoints. Class-level
 * scope inherits to all endpoint methods. Endpoint methods are added by
 * follow-up commits.
 */
@JBossLog
@Tag(name = "executive")
@Path("/executive/people")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class ExecutivePeopleResource {

    @Inject
    ExecutivePeopleService executivePeopleService;

    /**
     * Splits a comma-separated UUID list query param into a Set; returns null for blank input.
     * Null means "no company filter" — services treat null as a flag to omit the company-filter
     * WHERE clause entirely; the {@code :companyIds} parameter is bound only when a non-null Set
     * is passed.
     */
    static Set<String> parseCommaSeparated(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Set<String> out = new HashSet<>();
        for (String s : raw.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out.isEmpty() ? null : out;
    }
}
