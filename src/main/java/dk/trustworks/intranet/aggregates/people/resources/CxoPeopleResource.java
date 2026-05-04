package dk.trustworks.intranet.aggregates.people.resources;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;
import dk.trustworks.intranet.aggregates.people.dto.cxo.ConsultantPyramidDTO;
import dk.trustworks.intranet.aggregates.people.dto.cxo.HeadcountGrowthMonthDTO;
import dk.trustworks.intranet.aggregates.people.dto.cxo.TurnoverTtmMonthDTO;
import dk.trustworks.intranet.aggregates.people.services.CxoPeopleService;
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
 * REST API for CxO Command Center people metrics — turnover TTM, consultant
 * pyramid, headcount growth, and related people-domain endpoints. Class-level
 * scope inherits to all endpoint methods.
 */
@JBossLog
@Tag(name = "people")
@Path("/people/cxo")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class CxoPeopleResource {

    @Inject
    CxoPeopleService cxoPeopleService;

    /**
     * Returns the trailing-24-months hires-vs-terminations curve.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/turnover-ttm")
    public List<TurnoverTtmMonthDTO> turnoverTtm(@QueryParam("companyIds") String companyIds) {
        return cxoPeopleService.turnoverTtm(CxoSqlSupport.parseCommaSeparated(companyIds));
    }

    /**
     * Returns the current consultant-pyramid distribution snapshot.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/consultant-pyramid")
    public ConsultantPyramidDTO consultantPyramid(@QueryParam("companyIds") String companyIds) {
        return cxoPeopleService.consultantPyramid(CxoSqlSupport.parseCommaSeparated(companyIds));
    }

    /**
     * Returns the trailing-24-months headcount-by-type curve.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/headcount-growth")
    public List<HeadcountGrowthMonthDTO> headcountGrowth(@QueryParam("companyIds") String companyIds) {
        return cxoPeopleService.headcountGrowth(CxoSqlSupport.parseCommaSeparated(companyIds));
    }
}
