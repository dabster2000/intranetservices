package dk.trustworks.intranet.aggregates.sales.resources;

import dk.trustworks.intranet.aggregates.sales.services.CxoSalesService;
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
 * REST API for CxO Command Center sales metrics — pipeline trend, backlog coverage,
 * and pipeline funnel. Class-level scope inherits to all endpoint methods.
 */
@JBossLog
@Tag(name = "sales")
@Path("/sales/cxo")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class CxoSalesResource {

    @Inject
    CxoSalesService cxoSalesService;

    /**
     * Splits a comma-separated UUID list query param into a Set; returns null for blank input.
     * Null means "no company filter" — services bind null to a SQL parameter and the WHERE clause
     * `(:companyIds IS NULL OR fact.company_id IN (:companyIds))` short-circuits.
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
