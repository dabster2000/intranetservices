package dk.trustworks.intranet.aggregates.executive.resources;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecAgeBucketDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecGenderTrendMonthDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecHeadcountByTypeMonthDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecCareerLevelDistDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecRetentionCohortDTO;
import dk.trustworks.intranet.aggregates.executive.services.ExecutivePeopleService;
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
     * Returns the current-snapshot age distribution of active employees in
     * 5-year buckets, stacked by gender.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/age-distribution")
    @Deprecated(forRemoval = false)
    public List<ExecAgeBucketDTO> ageDistribution(@QueryParam("companyIds") String companyIds) {
        return executivePeopleService.ageDistribution(CxoSqlSupport.parseCommaSeparated(companyIds));
    }

    /**
     * Returns the trailing-24-months gender diversity trend.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/gender-trend")
    public List<ExecGenderTrendMonthDTO> genderTrend(@QueryParam("companyIds") String companyIds) {
        return executivePeopleService.genderTrend(CxoSqlSupport.parseCommaSeparated(companyIds));
    }

    /**
     * Returns the trailing-24-months headcount-by-type curve including EXTERNAL.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/headcount-by-type")
    public List<ExecHeadcountByTypeMonthDTO> headcountByType(@QueryParam("companyIds") String companyIds) {
        return executivePeopleService.headcountByType(CxoSqlSupport.parseCommaSeparated(companyIds));
    }

    /**
     * Returns hire-year cohort survival curves for cohorts 2019–2025.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/retention-cohorts")
    public List<ExecRetentionCohortDTO> retentionCohorts(@QueryParam("companyIds") String companyIds) {
        return executivePeopleService.retentionCohorts(CxoSqlSupport.parseCommaSeparated(companyIds));
    }

    /**
     * Returns the current-snapshot career-level distribution for active consultants.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/career-level-distribution")
    public List<ExecCareerLevelDistDTO> careerLevelDistribution(@QueryParam("companyIds") String companyIds) {
        return executivePeopleService.careerLevelDistribution(CxoSqlSupport.parseCommaSeparated(companyIds));
    }
}
