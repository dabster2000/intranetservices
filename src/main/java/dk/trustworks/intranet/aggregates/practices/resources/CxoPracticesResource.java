package dk.trustworks.intranet.aggregates.practices.resources;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticesGrossMarginMonthDTO;
import dk.trustworks.intranet.aggregates.practices.services.CxoPracticesService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST API for CxO Command Center practices metrics — gross margin, bench FTE,
 * and related practice-level KPIs. Class-level scope inherits to all endpoint
 * methods.
 */
@JBossLog
@Tag(name = "practices")
@Path("/practices/cxo")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class CxoPracticesResource {

    @Inject
    CxoPracticesService cxoPracticesService;

    /**
     * Returns gross-margin % TTM and prior-period delta per practice.
     * Mirrors the BFF route at {@code /api/cxo/practices/gross-margin}.
     *
     * <p>Always returns 5 rows in fixed order: PM, BA, CYB, DEV, SA.</p>
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/gross-margin")
    public List<PracticesGrossMarginMonthDTO> grossMargin(
            @QueryParam("companyIds") String companyIds) {
        return cxoPracticesService.grossMargin(CxoSqlSupport.parseCommaSeparated(companyIds));
    }
}
