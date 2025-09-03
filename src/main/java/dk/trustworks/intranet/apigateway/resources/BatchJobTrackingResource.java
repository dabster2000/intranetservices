package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.batch.monitoring.BatchJobExecutionTracking;
import dk.trustworks.intranet.batch.monitoring.BatchJobTrackingQuery;
import dk.trustworks.intranet.batch.monitoring.BatchJobTrackingQuery.PageResult;
import dk.trustworks.intranet.batch.monitoring.BatchJobTrackingQuery.Summary;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "batch-tracking")
@Path("/batch/tracking")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class BatchJobTrackingResource {

    @Inject
    BatchJobTrackingQuery query;

    @GET
    @Path("/executions")
    public PageDto<ExecutionDto> list(
            @QueryParam("jobName") String jobName,
            @QueryParam("status") String status,
            @QueryParam("result") String result,
            @QueryParam("runningOnly") @DefaultValue("false") boolean runningOnly,
            @QueryParam("startFrom") String startFrom,
            @QueryParam("startTo") String startTo,
            @QueryParam("endFrom") String endFrom,
            @QueryParam("endTo") String endTo,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size,
            @QueryParam("sort") @DefaultValue("startTime,desc") String sort
    ) {
        LocalDateTime sf = parseDateTime(startFrom).orElse(null);
        LocalDateTime st = parseDateTime(startTo).orElse(null);
        LocalDateTime ef = parseDateTime(endFrom).orElse(null);
        LocalDateTime et = parseDateTime(endTo).orElse(null);
        PageResult<BatchJobExecutionTracking> res = query.search(jobName, status, result, runningOnly, sf, st, ef, et, page, size, sort);
        return PageDto.of(res, ExecutionDto::from);
    }

    @GET
    @Path("/executions/{executionId}")
    public Response getOne(@PathParam("executionId") long executionId) {
        return query.findByExecutionId(executionId)
                .map(ExecutionDto::from)
                .map(Response::ok)
                .orElse(Response.status(Response.Status.NOT_FOUND))
                .build();
    }

    // In BatchJobTrackingResource

    @GET
    @Path("/executions/{executionId}/trace")
    public Response trace(@PathParam("executionId") long executionId) {
        return query.findByExecutionId(executionId)
                .map(e -> Response.ok(new TraceDto(
                        Optional.ofNullable(e.getTraceLog()).orElse("")
                )))
                .orElse(Response.status(Response.Status.NOT_FOUND))
                .build();
    }

    public record TraceDto(String trace) {}


    @GET
    @Path("/running")
    public PageDto<ExecutionDto> running(
            @QueryParam("jobName") String jobName,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size
    ) {
        PageResult<BatchJobExecutionTracking> res = query.search(jobName, null, null, true, null, null, null, null, page, size, "startTime,desc");
        return PageDto.of(res, ExecutionDto::from);
    }

    @GET
    @Path("/summary")
    public Summary summary(@QueryParam("jobName") String jobName) {
        return query.summary(jobName);
    }

    private Optional<LocalDateTime> parseDateTime(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        try {
            return Optional.of(LocalDateTime.parse(s));
        } catch (DateTimeParseException e) {
            // Fallback: try seconds precision without nanos
            try { return Optional.of(LocalDateTime.parse(s.replace(" ", "T"))); }
            catch (Exception ignored) { return Optional.empty(); }
        }
    }

    // DTOs to keep the REST surface stable and avoid exposing JPA entities directly
    public record ExecutionDto(
            long id,
            String jobName,
            long executionId,
            String status,
            String result,
            String exitStatus,
            Integer progressPercent,
            Integer totalSubtasks,
            Integer completedSubtasks,
            String startTime,
            String endTime,
            String details
    ) {
        public static ExecutionDto from(BatchJobExecutionTracking e) {
            return new ExecutionDto(
                    e.getId() == null ? 0L : e.getId(),
                    e.getJobName(),
                    e.getExecutionId(),
                    e.getStatus(),
                    e.getResult(),
                    e.getExitStatus(),
                    e.getProgressPercent(),
                    e.getTotalSubtasks(),
                    e.getCompletedSubtasks(),
                    e.getStartTime() != null ? e.getStartTime().toString() : null,
                    e.getEndTime() != null ? e.getEndTime().toString() : null,
                    e.getDetails()
            );
        }
    }

    public static class PageDto<T> {
        public List<T> items;
        public int page;
        public int size;
        public long total;
        public static <S, T> PageDto<T> of(PageResult<S> res, java.util.function.Function<S, T> mapper) {
            PageDto<T> dto = new PageDto<>();
            dto.items = res.items.stream().map(mapper).toList();
            dto.page = res.page;
            dto.size = res.size;
            dto.total = res.total;
            return dto;
        }
    }
}
