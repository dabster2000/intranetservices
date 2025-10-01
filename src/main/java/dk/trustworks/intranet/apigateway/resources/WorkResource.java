package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.work.events.UpdateWorkEvent;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.dto.work.LightweightWork;
import dk.trustworks.intranet.dto.work.PagedWorkResponse;
import dk.trustworks.intranet.dto.work.WorkByUserResponse;
import dk.trustworks.intranet.dto.work.WorkSummary;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "time")
@Path("/")
@JBossLog
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class WorkResource {

    @Inject
    WorkService workAPI;

    @Inject
    AggregateEventSender sender;

    @GET
    @Path("/work")
    public List<WorkFull> listAll(@QueryParam("page") Integer page) {
        log.debug("WorkResource.listAll");
        log.debug("page = " + page);
        return workAPI.listAll(page);
    }

    @GET
    @Path("/work/search/findByPeriodAndUserAndTasks")
    public List<WorkFull> findByPeriodAndUserAndTasks(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate, @QueryParam("useruuid") String useruuid, @QueryParam("taskuuids") String taskuuids) {
        return workAPI.findByPeriodAndUserAndTasks(dateIt(fromdate), dateIt(todate), useruuid, taskuuids);
    }

    @GET
    @Path("/work/search/findByUserAndTasks")
    public List<WorkFull> findByUserAndTasks(@QueryParam("useruuid") String useruuid, @QueryParam("taskuuids") String taskuuids) {
        return workAPI.findWorkFullByUserAndTasks(useruuid, taskuuids);
    }

    @GET
    @Path("/work/search/findByTasks")
    public List<WorkFull> findByTasks(@QueryParam("taskuuids") List<String> taskuuids) {
        return workAPI.findByTasks(taskuuids);
    }

    @GET
    @Path("/work/search/findByPeriod")
    public List<WorkFull> findByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return workAPI.findByPeriod(dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/work/workduration/sum")
    public KeyValueDTO sumWorkdurationByUserAndTasks(@QueryParam("useruuid") String useruuid, @QueryParam("taskuuids") String taskuuids) {
        return new KeyValueDTO("", Double.toString(workAPI.countByUserAndTasks(useruuid, taskuuids)));
    }

    @GET
    @Path("/work/billable/sum")
    public double sumBillableByUserAndTasks(@QueryParam("useruuid") String useruuid, @QueryParam("month") String month) {
        return workAPI.sumBillableByUserAndTasks(useruuid, DateUtils.dateIt(month));
    }

    @POST
    @Path("/work")
    public void save(Work work) {
        workAPI.persistOrUpdate(work);

        sender.handleEvent(new UpdateWorkEvent(work.getUseruuid(), work));
        if(work.getWorkas()!=null && !work.getWorkas().isEmpty())
            sender.handleEvent(new UpdateWorkEvent(work.getWorkas(), work));
    }
/*
    @GET
    @Path("/tasks/{uuid}/work")
    public List<WorkFull> getWorkByTask(@PathParam("uuid") String taskuuid) {
        return workAPI.findByTask(taskuuid);
    }

 */

    // ============================================================================
    // Performance-Optimized Endpoints (Added for large dataset handling)
    // ============================================================================

    @GET
    @Path("/work/search/findByPeriodPaged")
    @Operation(
            summary = "Get paginated work data by period",
            description = "Retrieves work entries for a date range with pagination support. " +
                    "Optimized for large datasets to avoid memory issues. " +
                    "Use this endpoint when dealing with date ranges larger than 1 month."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Successfully retrieved paginated work data",
                    content = @Content(
                            mediaType = APPLICATION_JSON,
                            schema = @Schema(implementation = PagedWorkResponse.class)
                    )
            ),
            @APIResponse(
                    responseCode = "400",
                    description = "Invalid date format or pagination parameters"
            )
    })
    public PagedWorkResponse findByPeriodPaged(
            @Parameter(
                    description = "Start date (inclusive) in format YYYY-MM-DD",
                    required = true,
                    example = "2024-01-01"
            )
            @QueryParam("fromdate") String fromdate,

            @Parameter(
                    description = "End date (exclusive) in format YYYY-MM-DD",
                    required = true,
                    example = "2024-02-01"
            )
            @QueryParam("todate") String todate,

            @Parameter(
                    description = "Page number (0-based)",
                    required = false,
                    example = "0"
            )
            @QueryParam("page") @DefaultValue("0") int page,

            @Parameter(
                    description = "Number of records per page (max 1000)",
                    required = false,
                    example = "100"
            )
            @QueryParam("size") @DefaultValue("100") int size) {

        log.debugf("findByPeriodPaged: fromdate=%s, todate=%s, page=%d, size=%d",
                fromdate, todate, page, size);

        // Limit page size to prevent memory issues
        size = Math.min(size, 1000);

        LocalDate from = dateIt(fromdate);
        LocalDate to = dateIt(todate);

        long totalElements = workAPI.countByPeriod(from, to);
        List<WorkFull> content = workAPI.findByPeriodPaged(from, to, page, size);

        return PagedWorkResponse.of(content, page, size, totalElements);
    }

    @GET
    @Path("/work/search/findByPeriodLightweight")
    @Operation(
            summary = "Get lightweight work data by period",
            description = "Retrieves only essential work fields for optimal performance. " +
                    "Returns simplified work records without full entity relationships. " +
                    "Use this for reports, exports, or when full work details are not needed."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Successfully retrieved lightweight work data",
                    content = @Content(
                            mediaType = APPLICATION_JSON,
                            schema = @Schema(implementation = LightweightWork[].class)
                    )
            ),
            @APIResponse(
                    responseCode = "400",
                    description = "Invalid date format"
            )
    })
    public List<LightweightWork> findByPeriodLightweight(
            @Parameter(
                    description = "Start date (inclusive) in format YYYY-MM-DD",
                    required = true,
                    example = "2024-01-01"
            )
            @QueryParam("fromdate") String fromdate,

            @Parameter(
                    description = "End date (exclusive) in format YYYY-MM-DD",
                    required = true,
                    example = "2024-02-01"
            )
            @QueryParam("todate") String todate) {

        log.debugf("findByPeriodLightweight: fromdate=%s, todate=%s", fromdate, todate);

        LocalDate from = dateIt(fromdate);
        LocalDate to = dateIt(todate);

        List<Map<String, Object>> rawData = workAPI.findByPeriodLightweight(from, to);

        // Convert raw data to DTOs
        return rawData.stream()
                .map(row -> LightweightWork.builder()
                        .uuid((String) row.get("uuid"))
                        .useruuid((String) row.get("useruuid"))
                        .registered((LocalDate) row.get("registered"))
                        .workduration((Double) row.get("workduration"))
                        .taskuuid((String) row.get("taskuuid"))
                        .billable((Boolean) row.get("billable"))
                        .rate((Double) row.get("rate"))
                        .projectuuid((String) row.get("projectuuid"))
                        .build())
                .collect(Collectors.toList());
    }

    @GET
    @Path("/work/search/countByPeriod")
    @Operation(
            summary = "Count work entries by period",
            description = "Returns the total count of work entries for a date range. " +
                    "Useful for pagination metadata or statistics without fetching actual data."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Successfully counted work entries",
                    content = @Content(
                            mediaType = APPLICATION_JSON,
                            schema = @Schema(type = SchemaType.INTEGER, format = "int64")
                    )
            ),
            @APIResponse(
                    responseCode = "400",
                    description = "Invalid date format"
            )
    })
    public long countByPeriod(
            @Parameter(
                    description = "Start date (inclusive) in format YYYY-MM-DD",
                    required = true,
                    example = "2024-01-01"
            )
            @QueryParam("fromdate") String fromdate,

            @Parameter(
                    description = "End date (exclusive) in format YYYY-MM-DD",
                    required = true,
                    example = "2024-02-01"
            )
            @QueryParam("todate") String todate) {

        log.debugf("countByPeriod: fromdate=%s, todate=%s", fromdate, todate);

        return workAPI.countByPeriod(dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/work/search/findByPeriodGroupedByUser")
    @Operation(
            summary = "Get work data grouped by user",
            description = "Retrieves work entries grouped by user for the specified period. " +
                    "Optimized for user-centric reports and analytics. " +
                    "Each user's work is fetched in batches to minimize memory usage."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Successfully retrieved work data grouped by user",
                    content = @Content(
                            mediaType = APPLICATION_JSON,
                            schema = @Schema(implementation = WorkByUserResponse.class)
                    )
            ),
            @APIResponse(
                    responseCode = "400",
                    description = "Invalid date format"
            )
    })
    public WorkByUserResponse findByPeriodGroupedByUser(
            @Parameter(
                    description = "Start date (inclusive) in format YYYY-MM-DD",
                    required = true,
                    example = "2024-01-01"
            )
            @QueryParam("fromdate") String fromdate,

            @Parameter(
                    description = "End date (exclusive) in format YYYY-MM-DD",
                    required = true,
                    example = "2024-02-01"
            )
            @QueryParam("todate") String todate) {

        log.debugf("findByPeriodGroupedByUser: fromdate=%s, todate=%s", fromdate, todate);

        LocalDate from = dateIt(fromdate);
        LocalDate to = dateIt(todate);

        Map<String, List<WorkFull>> workByUser = workAPI.findByPeriodGroupedByUser(from, to);

        return WorkByUserResponse.of(workByUser, from, to);
    }

    @GET
    @Path("/work/search/summaryByPeriod")
    @Operation(
            summary = "Get work summary statistics by period",
            description = "Returns aggregated statistics for work entries in the specified period. " +
                    "Provides high-level metrics without fetching individual work records. " +
                    "Ideal for dashboards, reports, and overview screens."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Successfully retrieved work summary",
                    content = @Content(
                            mediaType = APPLICATION_JSON,
                            schema = @Schema(implementation = WorkSummary.class)
                    )
            ),
            @APIResponse(
                    responseCode = "400",
                    description = "Invalid date format"
            )
    })
    public WorkSummary getWorkSummaryByPeriod(
            @Parameter(
                    description = "Start date (inclusive) in format YYYY-MM-DD",
                    required = true,
                    example = "2024-01-01"
            )
            @QueryParam("fromdate") String fromdate,

            @Parameter(
                    description = "End date (exclusive) in format YYYY-MM-DD",
                    required = true,
                    example = "2024-02-01"
            )
            @QueryParam("todate") String todate) {

        log.debugf("getWorkSummaryByPeriod: fromdate=%s, todate=%s", fromdate, todate);

        LocalDate from = dateIt(fromdate);
        LocalDate to = dateIt(todate);

        Map<String, Object> summaryData = workAPI.getWorkSummaryByPeriod(from, to);

        return WorkSummary.builder()
                .uniqueUsers((Integer) summaryData.get("uniqueUsers"))
                .uniqueTasks((Integer) summaryData.get("uniqueTasks"))
                .uniqueProjects((Integer) summaryData.get("uniqueProjects"))
                .totalHours((Double) summaryData.get("totalHours"))
                .totalRevenue((Double) summaryData.get("totalRevenue"))
                .totalEntries((Long) summaryData.get("totalEntries"))
                .fromDate(from)
                .toDate(to)
                .build();
    }

}
