package dk.trustworks.intranet.aggregates.executive.resources;

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

import java.util.HashSet;
import java.util.List;
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

    /**
     * Returns the current-snapshot age distribution of active employees in
     * 5-year buckets, stacked by gender.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/age-distribution")
    public List<ExecAgeBucketDTO> ageDistribution(@QueryParam("companyIds") String companyIds) {
        return executivePeopleService.ageDistribution(parseCommaSeparated(companyIds));
    }

    /**
     * Returns the trailing-24-months gender diversity trend.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/gender-trend")
    public List<ExecGenderTrendMonthDTO> genderTrend(@QueryParam("companyIds") String companyIds) {
        return executivePeopleService.genderTrend(parseCommaSeparated(companyIds));
    }

    /**
     * Returns the trailing-24-months headcount-by-type curve including EXTERNAL.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/headcount-by-type")
    public List<ExecHeadcountByTypeMonthDTO> headcountByType(@QueryParam("companyIds") String companyIds) {
        return executivePeopleService.headcountByType(parseCommaSeparated(companyIds));
    }

    /**
     * Returns hire-year cohort survival curves for cohorts 2019–2025.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/retention-cohorts")
    public List<ExecRetentionCohortDTO> retentionCohorts(@QueryParam("companyIds") String companyIds) {
        return executivePeopleService.retentionCohorts(parseCommaSeparated(companyIds));
    }

    /**
     * Returns the current-snapshot career-level distribution for active consultants.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means no filter
     */
    @GET
    @Path("/career-level-distribution")
    public List<ExecCareerLevelDistDTO> careerLevelDistribution(@QueryParam("companyIds") String companyIds) {
        return executivePeopleService.careerLevelDistribution(parseCommaSeparated(companyIds));
    }
}
