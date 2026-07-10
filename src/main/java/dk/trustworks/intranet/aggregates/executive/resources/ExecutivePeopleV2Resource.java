package dk.trustworks.intranet.aggregates.executive.resources;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.CareerLadderRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.CareerMixRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.GenderTrendPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.HeadcountCompositionPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.LeadershipCoverageDetail;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.LeadershipCoverageRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayEquityRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayTrendPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PracticeCareerCell;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.Response;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.RetentionCohort;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.RetentionRatePoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.StatusTrendPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.TenureBand;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.UpcomingChangeDetail;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.UpcomingChanges;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.WorkforceFlowPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.WorkforceSummary;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterParams;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterRequest;
import dk.trustworks.intranet.aggregates.executive.people.PeoplePopulationScope;
import dk.trustworks.intranet.aggregates.executive.people.PeopleWorkforceEventType;
import dk.trustworks.intranet.aggregates.executive.services.ExecutivePeopleCareerService;
import dk.trustworks.intranet.aggregates.executive.services.ExecutivePeopleRetentionPayService;
import dk.trustworks.intranet.aggregates.executive.services.ExecutivePeopleWorkforceService;
import dk.trustworks.intranet.security.ScopeContext;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/** Versioned, canonical Executive HR & People API. */
@Tag(name = "executive")
@Path("/executive/people/v2")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class ExecutivePeopleV2Resource {

    private static final Set<String> COMMON_QUERY_KEYS = Set.of(
            "asOfDate", "months", "horizonDays", "companyId", "employeeTypes", "population",
            "practices", "careerTracks", "careerLevels", "managementScope", "compensationGroup", "salaryType");
    static final Set<String> WORKFORCE_SUMMARY_KEYS = Set.of(
            "asOfDate", "companyId", "employeeTypes", "practices");
    static final Set<String> SNAPSHOT_KEYS = Set.of(
            "asOfDate", "companyId", "employeeTypes", "population", "practices");
    static final Set<String> TREND_KEYS = Set.of(
            "asOfDate", "months", "companyId", "employeeTypes", "population", "practices");
    static final Set<String> EVENT_TREND_KEYS = Set.of(
            "asOfDate", "months", "companyId", "employeeTypes", "practices");
    static final Set<String> UPCOMING_KEYS = Set.of(
            "asOfDate", "horizonDays", "companyId", "employeeTypes", "practices");
    static final Set<String> CAREER_KEYS = Set.of(
            "asOfDate", "companyId", "employeeTypes", "population", "practices", "careerTracks", "careerLevels", "managementScope");
    static final Set<String> LEADERSHIP_KEYS = Set.of("asOfDate", "companyId", "employeeTypes", "population");
    static final Set<String> RETENTION_KEYS = Set.of(
            "asOfDate", "months", "companyId", "employeeTypes", "population", "practices", "careerTracks", "careerLevels", "managementScope");
    static final Set<String> PAY_EQUITY_KEYS = Set.of(
            "asOfDate", "companyId", "employeeTypes", "population", "practices", "careerTracks", "careerLevels",
            "managementScope", "compensationGroup", "salaryType");
    static final Set<String> PAY_TREND_KEYS = Set.of(
            "asOfDate", "months", "companyId", "employeeTypes", "population", "practices", "careerTracks",
            "careerLevels", "managementScope", "salaryType");
    static final Map<String, Set<String>> ENDPOINT_QUERY_KEYS = Map.ofEntries(
            Map.entry("workforce-summary", WORKFORCE_SUMMARY_KEYS),
            Map.entry("headcount-composition", TREND_KEYS),
            Map.entry("status-trend", TREND_KEYS),
            Map.entry("gender-trend", TREND_KEYS),
            Map.entry("workforce-flow", EVENT_TREND_KEYS),
            Map.entry("upcoming-changes", UPCOMING_KEYS),
            Map.entry("upcoming-changes/detail", UPCOMING_KEYS),
            Map.entry("tenure-distribution", SNAPSHOT_KEYS),
            Map.entry("career-ladder", CAREER_KEYS),
            Map.entry("career-mix", CAREER_KEYS),
            Map.entry("practice-career-matrix", CAREER_KEYS),
            Map.entry("leadership-coverage", LEADERSHIP_KEYS),
            Map.entry("leadership-coverage/detail", LEADERSHIP_KEYS),
            Map.entry("retention-rate", RETENTION_KEYS),
            Map.entry("retention-cohorts", RETENTION_KEYS),
            Map.entry("pay-equity", PAY_EQUITY_KEYS),
            Map.entry("pay-trend", PAY_TREND_KEYS));

    @Inject
    ExecutivePeopleWorkforceService workforceService;

    @Inject
    ExecutivePeopleCareerService careerService;

    @Inject
    ExecutivePeopleRetentionPayService retentionPayService;

    @Inject
    ScopeContext scopeContext;

    @Context
    UriInfo uriInfo;

    @GET
    @Path("/workforce-summary")
    public Response<WorkforceSummary> workforceSummary(@BeanParam PeopleFilterRequest request) {
        return workforceService.workforceSummary(filters(request, WORKFORCE_SUMMARY_KEYS));
    }

    @GET
    @Path("/headcount-composition")
    public Response<List<HeadcountCompositionPoint>> headcountComposition(@BeanParam PeopleFilterRequest request) {
        return workforceService.headcountComposition(filters(request, TREND_KEYS));
    }

    @GET
    @Path("/status-trend")
    public Response<List<StatusTrendPoint>> statusTrend(@BeanParam PeopleFilterRequest request) {
        return workforceService.statusTrend(filters(request, TREND_KEYS));
    }

    @GET
    @Path("/gender-trend")
    public Response<List<GenderTrendPoint>> genderTrend(@BeanParam PeopleFilterRequest request) {
        return workforceService.genderTrend(filters(request, TREND_KEYS));
    }

    @GET
    @Path("/workforce-flow")
    public Response<List<WorkforceFlowPoint>> workforceFlow(@BeanParam PeopleFilterRequest request) {
        return workforceService.workforceFlow(filters(request, EVENT_TREND_KEYS));
    }

    @GET
    @Path("/upcoming-changes")
    public Response<UpcomingChanges> upcomingChanges(@BeanParam PeopleFilterRequest request) {
        return workforceService.upcomingChanges(filters(request, UPCOMING_KEYS));
    }

    @GET
    @Path("/upcoming-changes/detail")
    @RolesAllowed({"admin:*"})
    public Response<List<UpcomingChangeDetail>> upcomingChangesDetail(
            @BeanParam PeopleFilterRequest request,
            @QueryParam("eventDate") String eventDate,
            @QueryParam("eventType") String eventType) {
        PeopleFilterParams filters = filters(request, UPCOMING_KEYS, "eventDate", "eventType");
        return workforceService.upcomingChangesDetail(
                filters,
                requiredDate("eventDate", eventDate),
                requiredEnum("eventType", eventType, PeopleWorkforceEventType.class));
    }

    @GET
    @Path("/tenure-distribution")
    public Response<List<TenureBand>> tenureDistribution(@BeanParam PeopleFilterRequest request) {
        return workforceService.tenureDistribution(filters(request, SNAPSHOT_KEYS));
    }

    @GET
    @Path("/career-ladder")
    public Response<List<CareerLadderRow>> careerLadder(@BeanParam PeopleFilterRequest request) {
        return careerService.careerLadder(careerFilters(request));
    }

    @GET
    @Path("/career-mix")
    public Response<List<CareerMixRow>> careerMix(@BeanParam PeopleFilterRequest request) {
        return careerService.careerMix(careerFilters(request));
    }

    @GET
    @Path("/practice-career-matrix")
    public Response<List<PracticeCareerCell>> practiceCareerMatrix(@BeanParam PeopleFilterRequest request) {
        return careerService.practiceCareerMatrix(careerFilters(request));
    }

    @GET
    @Path("/leadership-coverage")
    public Response<List<LeadershipCoverageRow>> leadershipCoverage(@BeanParam PeopleFilterRequest request) {
        return careerService.leadershipCoverage(leadershipFilters(request));
    }

    @GET
    @Path("/leadership-coverage/detail")
    @RolesAllowed({"admin:*"})
    public Response<List<LeadershipCoverageDetail>> leadershipCoverageDetail(
            @BeanParam PeopleFilterRequest request,
            @QueryParam("teamId") String teamId) {
        return careerService.leadershipCoverageDetail(
                leadershipFilters(request, "teamId"), requiredUuid("teamId", teamId));
    }

    @GET
    @Path("/retention-rate")
    public Response<List<RetentionRatePoint>> retentionRate(@BeanParam PeopleFilterRequest request) {
        return retentionPayService.retentionRate(filters(request, RETENTION_KEYS));
    }

    @GET
    @Path("/retention-cohorts")
    public Response<List<RetentionCohort>> retentionCohorts(@BeanParam PeopleFilterRequest request) {
        return retentionPayService.retentionCohorts(filters(request, RETENTION_KEYS));
    }

    @GET
    @Path("/pay-equity")
    public Response<List<PayEquityRow>> payEquity(@BeanParam PeopleFilterRequest request) {
        requireSalaryScope();
        return retentionPayService.payEquity(filters(request, PAY_EQUITY_KEYS));
    }

    @GET
    @Path("/pay-trend")
    public Response<List<PayTrendPoint>> payTrend(@BeanParam PeopleFilterRequest request) {
        requireSalaryScope();
        return retentionPayService.payTrend(filters(request, PAY_TREND_KEYS));
    }

    private void requireSalaryScope() {
        if (!scopeContext.hasAllScopes("dashboard:read", "salaries:read")) {
            throw new ForbiddenException();
        }
    }

    private PeopleFilterParams careerFilters(PeopleFilterRequest request) {
        applyCareerDefaults(request);
        PeopleFilterParams filters = filters(request, CAREER_KEYS);
        validateCareerInvariants(filters);
        return filters;
    }

    static void applyCareerDefaults(PeopleFilterRequest request) {
        if (request.employeeTypes == null) request.employeeTypes = ConsultantType.CONSULTANT.name();
    }

    private PeopleFilterParams leadershipFilters(PeopleFilterRequest request, String... detailKeys) {
        PeopleFilterParams filters = filters(request, LEADERSHIP_KEYS, detailKeys);
        validateLeadershipInvariants(filters);
        return filters;
    }

    static void validateCareerInvariants(PeopleFilterParams filters) {
        if (!filters.employeeTypes().equals(Set.of(ConsultantType.CONSULTANT))
                || filters.population() != PeoplePopulationScope.EMPLOYED) {
            throw new BadRequestException(
                    "Career endpoints require employeeTypes=CONSULTANT and population=EMPLOYED");
        }
    }

    static void validateLeadershipInvariants(PeopleFilterParams filters) {
        if (!filters.employeeTypes().equals(PeopleFilterParams.DEFAULT_INTERNAL_TYPES)
                || filters.population() != PeoplePopulationScope.EMPLOYED) {
            throw new BadRequestException(
                    "Leadership endpoints require all internal employeeTypes and population=EMPLOYED");
        }
    }

    private PeopleFilterParams filters(PeopleFilterRequest request, Set<String> endpointKeys, String... detailKeys) {
        Set<String> allowed = new HashSet<>(endpointKeys);
        allowed.addAll(List.of(detailKeys));
        validateQueryParameters(uriInfo.getQueryParameters(), allowed);
        return PeopleFilterParams.from(request);
    }

    static void validateQueryParameters(MultivaluedMap<String, String> queryParameters, Set<String> allowed) {
        queryParameters.forEach((key, values) -> {
            if (!allowed.contains(key)) {
                String prefix = COMMON_QUERY_KEYS.contains(key)
                        ? "Query parameter is not applicable to this endpoint: "
                        : "Unknown query parameter: ";
                throw new BadRequestException(prefix + key);
            }
            if (values.size() != 1) {
                throw new BadRequestException("Query parameter must be supplied once: " + key);
            }
        });
    }

    private static LocalDate requiredDate(String name, String raw) {
        if (raw == null || raw.isBlank()) throw new BadRequestException(name + " is required");
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeException exception) {
            throw new BadRequestException(name + " must be an ISO date (YYYY-MM-DD)");
        }
    }

    private static String requiredUuid(String name, String raw) {
        if (raw == null || raw.isBlank()) throw new BadRequestException(name + " is required");
        String value = raw.trim();
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(name + " must be a canonical UUID");
        }
    }

    private static <E extends Enum<E>> E requiredEnum(String name, String raw, Class<E> enumType) {
        if (raw == null || raw.isBlank()) throw new BadRequestException(name + " is required");
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid " + name);
        }
    }
}
