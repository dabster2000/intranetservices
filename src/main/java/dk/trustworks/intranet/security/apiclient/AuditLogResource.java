package dk.trustworks.intranet.security.apiclient;

import dk.trustworks.intranet.security.apiclient.dto.AuditLogPageResponse;
import dk.trustworks.intranet.security.apiclient.model.AuditEventType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * Read-only REST resource for querying the API client audit log.
 * Supports server-side pagination and optional filters by client,
 * event type, and time range.
 */
@Path("/auth/audit-log")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin:*", "ADMIN", "SYSTEM"})
@JBossLog
public class AuditLogResource {

    private static final int MAX_PAGE_SIZE = 200;

    @Inject
    ApiClientRepository repository;

    @GET
    public Response query(
            @QueryParam("client_uuid") String clientUuid,
            @QueryParam("event_type") String eventTypeParam,
            @QueryParam("from") String fromParam,
            @QueryParam("to") String toParam,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size) {

        // Clamp size to the allowed maximum
        int effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        // Validate client_uuid format if provided
        if (clientUuid != null && !clientUuid.isBlank()
                && !clientUuid.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
            throw new BadRequestException("Invalid client_uuid format. Expected UUID (e.g. a1b2c3d4-e5f6-7890-abcd-ef1234567890).");
        }

        // Parse optional event_type filter (comma-separated)
        List<AuditEventType> eventTypes = parseEventTypes(eventTypeParam);

        // Parse optional datetime range filters
        LocalDateTime from = parseDateTime(fromParam, "from");
        LocalDateTime to = parseDateTime(toParam, "to");

        var items = repository.findAuditLogPaginated(clientUuid, eventTypes, from, to, page, effectiveSize);
        long totalCount = repository.countAuditLog(clientUuid, eventTypes, from, to);

        return Response.ok(new AuditLogPageResponse(items, totalCount, page, effectiveSize)).build();
    }

    private List<AuditEventType> parseEventTypes(String eventTypeParam) {
        if (eventTypeParam == null || eventTypeParam.isBlank()) {
            return null;
        }
        try {
            return Arrays.stream(eventTypeParam.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(AuditEventType::valueOf)
                    .toList();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid event_type value. Valid values: "
                    + Arrays.toString(AuditEventType.values()));
        }
    }

    private LocalDateTime parseDateTime(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(
                    "Invalid '" + paramName + "' parameter. Expected ISO datetime format (e.g. 2026-01-15T10:30:00).");
        }
    }
}
