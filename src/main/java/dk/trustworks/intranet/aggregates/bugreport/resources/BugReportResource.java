package dk.trustworks.intranet.aggregates.bugreport.resources;

import dk.trustworks.intranet.aggregates.bugreport.dto.*;
import dk.trustworks.intranet.aggregates.bugreport.services.AiSuggestionException;
import dk.trustworks.intranet.aggregates.bugreport.services.BugReportService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * REST resource for the Bug Report bounded context.
 * Thin layer: validation, auth, HTTP mapping, delegation to service.
 */
@Path("/bug-reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"bugreports:read"})
@JBossLog
public class BugReportResource {

    @Inject
    BugReportService bugReportService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    ScopeContext scopeContext;

    // ---- 1. POST /bug-reports — Create draft ----
    @POST
    @RolesAllowed({"bugreports:write"})
    public Response create(@Valid @NotNull BugReportCreateRequest request) {
        // HIGH-4 fix: Override client-supplied reporterUuid with authenticated user
        String authenticatedUuid = requestHeaderHolder.getUserUuid();
        var created = bugReportService.createDraft(request, authenticatedUuid);
        return Response.created(URI.create("/bug-reports/" + created.uuid()))
                .entity(created)
                .build();
    }

    // ---- 2. GET /bug-reports — List user's own reports ----
    @GET
    public Response listByReporter(
            @QueryParam("reporterUuid") String reporterUuid,
            @QueryParam("status") String status,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        if (reporterUuid == null || reporterUuid.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"reporterUuid query parameter is required\"}")
                    .build();
        }
        var result = bugReportService.findByReporter(reporterUuid, page, size);
        return Response.ok(result).build();
    }

    // ---- 3. GET /bug-reports/all — Admin: list all reports ----
    @GET
    @Path("/all")
    @RolesAllowed({"bugreports:admin"})
    public Response listAll(
            @QueryParam("status") String status,
            @QueryParam("search") String search,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        var result = bugReportService.findAll(status, search, page, size);
        return Response.ok(result).build();
    }

    // ---- 4. GET /bug-reports/{uuid} — Get single report ----
    @GET
    @Path("/{uuid}")
    public Response getByUuid(@PathParam("uuid") String uuid) {
        return bugReportService.findByUuid(uuid)
                .map(dto -> {
                    // IDOR fix: non-admin users can only view their own reports
                    String callerUuid = requestHeaderHolder.getUserUuid();
                    if (!dto.reporterUuid().equals(callerUuid) && !hasAdminScope()) {
                        return Response.status(Response.Status.FORBIDDEN)
                                .entity("{\"error\":\"Access denied\"}")
                                .build();
                    }
                    return Response.ok(dto).build();
                })
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Bug report not found\"}")
                        .build());
    }

    // ---- 5. PUT /bug-reports/{uuid} — Update own report ----
    @PUT
    @Path("/{uuid}")
    @RolesAllowed({"bugreports:write"})
    public Response update(@PathParam("uuid") String uuid,
                           @HeaderParam("If-Match") String ifMatchHeader,
                           @Valid @NotNull BugReportUpdateRequest request) {
        String userUuid = requestHeaderHolder.getUserUuid();
        LocalDateTime ifMatch = parseIfMatch(ifMatchHeader);
        try {
            var updated = bugReportService.update(uuid, userUuid, request, ifMatch);
            return Response.ok(updated).build();
        } catch (IllegalStateException e) {
            return Response.status(409)
                    .entity("{\"error\":\"%s\"}".formatted(e.getMessage()))
                    .build();
        }
    }

    // ---- 6. DELETE /bug-reports/{uuid} — Hard-delete own DRAFT ----
    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"bugreports:write"})
    public Response delete(@PathParam("uuid") String uuid) {
        String userUuid = requestHeaderHolder.getUserUuid();
        try {
            bugReportService.deleteDraft(uuid, userUuid);
            return Response.noContent().build();
        } catch (SecurityException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"error\":\"%s\"}".formatted(e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(409)
                    .entity("{\"error\":\"%s\"}".formatted(e.getMessage()))
                    .build();
        }
    }

    // ---- 7. PUT /bug-reports/{uuid}/assign — Admin: assign ----
    @PUT
    @Path("/{uuid}/assign")
    @RolesAllowed({"bugreports:admin"})
    public Response assign(@PathParam("uuid") String uuid,
                           @HeaderParam("If-Match") String ifMatchHeader,
                           @Valid @NotNull AssignRequest request) {
        LocalDateTime ifMatch = parseIfMatch(ifMatchHeader);
        var updated = bugReportService.assign(uuid, request.assigneeUuid(), ifMatch);
        return Response.ok(updated).build();
    }

    // ---- 8. PUT /bug-reports/{uuid}/status — Admin: change status ----
    @PUT
    @Path("/{uuid}/status")
    @RolesAllowed({"bugreports:admin"})
    public Response changeStatus(@PathParam("uuid") String uuid,
                                 @HeaderParam("If-Match") String ifMatchHeader,
                                 @Valid @NotNull StatusChangeRequest request) {
        String actorUuid = requestHeaderHolder.getUserUuid();
        LocalDateTime ifMatch = parseIfMatch(ifMatchHeader);
        try {
            var updated = bugReportService.changeStatus(uuid, request.status(), actorUuid, ifMatch);
            return Response.ok(updated).build();
        } catch (IllegalStateException e) {
            return Response.status(409)
                    .entity("{\"error\":\"%s\"}".formatted(e.getMessage()))
                    .build();
        }
    }

    // ---- 9. GET /bug-reports/{uuid}/comments — List comments ----
    @GET
    @Path("/{uuid}/comments")
    public Response listComments(@PathParam("uuid") String uuid) {
        var comments = bugReportService.findComments(uuid);
        return Response.ok(comments).build();
    }

    // ---- 10. POST /bug-reports/{uuid}/comments — Add comment ----
    @POST
    @Path("/{uuid}/comments")
    @RolesAllowed({"bugreports:write"})
    public Response addComment(@PathParam("uuid") String uuid,
                               @Valid @NotNull BugReportCommentCreateRequest request) {
        String authorUuid = requestHeaderHolder.getUserUuid();
        var comment = bugReportService.addComment(uuid, authorUuid, request.content());
        return Response.created(URI.create("/bug-reports/" + uuid + "/comments/" + comment.uuid()))
                .entity(comment)
                .build();
    }

    // ---- 11. GET /bug-reports/{uuid}/screenshot — Get screenshot ----
    @GET
    @Path("/{uuid}/screenshot")
    @Produces("image/png")
    public Response getScreenshot(@PathParam("uuid") String uuid) {
        try {
            // IDOR fix: verify ownership before serving screenshot
            var report = bugReportService.findByUuid(uuid);
            if (report.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Bug report not found\"}")
                        .type(MediaType.APPLICATION_JSON_TYPE)
                        .build();
            }
            String callerUuid = requestHeaderHolder.getUserUuid();
            if (!report.get().reporterUuid().equals(callerUuid) && !hasAdminScope()) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity("{\"error\":\"Access denied\"}")
                        .type(MediaType.APPLICATION_JSON_TYPE)
                        .build();
            }
            byte[] screenshot = bugReportService.getScreenshot(uuid);
            return Response.ok(screenshot)
                    .type("image/png")
                    .header("Cache-Control", "private, no-cache")
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Screenshot not found\"}")
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .build();
        }
    }

    // ---- 12. POST /bug-reports/{uuid}/suggest — Per-field AI suggestion ----
    @POST
    @Path("/{uuid}/suggest")
    @RolesAllowed({"bugreports:write"})
    public Response suggest(@PathParam("uuid") String uuid,
                            @Valid @NotNull SuggestRequest request) {
        String callerUuid = requestHeaderHolder.getUserUuid();
        try {
            var suggestion = bugReportService.suggestField(uuid, request, callerUuid);
            return Response.ok(suggestion).build();
        } catch (SecurityException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"error\":\"%s\"}".formatted(e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"%s\"}".formatted(e.getMessage()))
                    .build();
        } catch (AiSuggestionException e) {
            return Response.status(502)
                    .entity("{\"error\":\"%s\"}".formatted(e.getMessage()))
                    .build();
        }
    }

    // ---- 13. GET /bug-reports/notifications — Get user's notifications ----
    @GET
    @Path("/notifications")
    public Response getNotifications(@QueryParam("userUuid") String userUuid) {
        String effectiveUuid = (userUuid != null && !userUuid.isBlank())
                ? userUuid : requestHeaderHolder.getUserUuid();
        var result = bugReportService.findNotifications(effectiveUuid);
        return Response.ok(result).build();
    }

    // ---- 14. PUT /bug-reports/notifications/{uuid}/read — Mark as read ----
    @PUT
    @Path("/notifications/{uuid}/read")
    @RolesAllowed({"bugreports:write"})
    public Response markNotificationAsRead(@PathParam("uuid") String uuid) {
        bugReportService.markNotificationAsRead(uuid);
        return Response.ok().build();
    }

    // ---- 15. PUT /bug-reports/notifications/read-all — Mark all as read ----
    @PUT
    @Path("/notifications/read-all")
    @RolesAllowed({"bugreports:write"})
    public Response markAllNotificationsAsRead(@QueryParam("userUuid") String userUuid) {
        String effectiveUuid = (userUuid != null && !userUuid.isBlank())
                ? userUuid : requestHeaderHolder.getUserUuid();
        bugReportService.markAllNotificationsAsRead(effectiveUuid);
        return Response.ok().build();
    }

    // ---- Helpers ----

    private boolean hasAdminScope() {
        return scopeContext.hasScope("bugreports:admin");
    }

    private LocalDateTime parseIfMatch(String ifMatchHeader) {
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            return null;
        }
        // Remove surrounding quotes if present
        String value = ifMatchHeader.replace("\"", "").trim();
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.debugf("Could not parse If-Match header: %s", ifMatchHeader);
            return null;
        }
    }
}
