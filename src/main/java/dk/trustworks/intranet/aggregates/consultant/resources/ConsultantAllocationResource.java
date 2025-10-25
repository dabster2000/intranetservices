package dk.trustworks.intranet.aggregates.consultant.resources;

import dk.trustworks.intranet.aggregates.consultant.services.ConsultantAllocationService;
import dk.trustworks.intranet.dto.contracts.ConsultantAllocationDTO;
import io.quarkus.cache.CacheResult;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * REST resource for consultant allocation analysis.
 *
 * <p>This resource provides endpoints to analyze consultant capacity across contracts
 * during a specified period, detecting over-allocation scenarios.
 *
 * <p><b>Endpoints:</b>
 * <ul>
 *   <li>GET /api/consultants/{userUuid}/allocation?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD - Get allocation</li>
 * </ul>
 *
 * <p><b>Caching:</b>
 * <ul>
 *   <li>Cache name: "consultant-allocation"</li>
 *   <li>TTL: 1 hour (configured in application.yml)</li>
 *   <li>Key: userUuid + startDate + endDate</li>
 * </ul>
 *
 * <p><b>Allocation Calculation:</b>
 * <ul>
 *   <li>Full-time: 40 hours/week = 100%</li>
 *   <li>Part-time: 20 hours/week = 50%</li>
 *   <li>Over-allocated: 60 hours/week = 150%</li>
 * </ul>
 *
 * <p><b>Example Request:</b>
 * <pre>
 * GET /api/consultants/user-123/allocation?startDate=2025-01-01&endDate=2025-12-31
 * </pre>
 *
 * <p><b>Example Response:</b>
 * <pre>
 * {
 *   "userUuid": "user-123",
 *   "consultantName": "John Doe",
 *   "startDate": "2025-01-01",
 *   "endDate": "2025-12-31",
 *   "totalAllocation": 80,
 *   "allocations": [
 *     {
 *       "contractUuid": "contract-456",
 *       "contractName": "Project Alpha",
 *       "clientName": "Acme Corp",
 *       "allocation": 50,
 *       "startDate": "2025-01-01",
 *       "endDate": "2025-06-30",
 *       "rate": 1200.0,
 *       "contractStatus": "SIGNED"
 *     }
 *   ],
 *   "isOverAllocated": false,
 *   "availableCapacity": 20
 * }
 * </pre>
 *
 * @see ConsultantAllocationService
 * @see ConsultantAllocationDTO
 * @since 1.0
 */
@Tag(name = "Consultant Allocation")
@Path("/api/consultants/{userUuid}/allocation")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
@JBossLog
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class ConsultantAllocationResource {

    @Inject
    ConsultantAllocationService allocationService;

    /**
     * Get consultant allocation across contracts for a specified period.
     *
     * <p>This endpoint calculates and returns consultant capacity metrics including:
     * <ul>
     *   <li>Total allocation percentage across all contracts</li>
     *   <li>Over-allocation detection (total > 100%)</li>
     *   <li>Available capacity (100 - total)</li>
     *   <li>Detailed breakdown per contract with allocation percentages</li>
     * </ul>
     *
     * <p><b>Date Format:</b> ISO-8601 format (YYYY-MM-DD)
     * <ul>
     *   <li>Valid: "2025-01-15", "2025-12-31"</li>
     *   <li>Invalid: "15/01/2025", "2025-1-15"</li>
     * </ul>
     *
     * <p><b>Performance:</b>
     * <ul>
     *   <li>First request: ~50-100ms (single optimized query)</li>
     *   <li>Cached requests: ~1-5ms (served from cache)</li>
     *   <li>Cache TTL: 1 hour</li>
     * </ul>
     *
     * <p><b>Error Handling:</b>
     * <ul>
     *   <li>400 Bad Request: Invalid UUID, missing/invalid dates, or startDate > endDate</li>
     *   <li>500 Internal Server Error: Database or service errors</li>
     * </ul>
     *
     * @param userUuid User UUID (consultant to analyze)
     * @param startDateStr Period start date in ISO-8601 format (YYYY-MM-DD)
     * @param endDateStr Period end date in ISO-8601 format (YYYY-MM-DD)
     * @return ConsultantAllocationDTO with allocation details
     * @throws jakarta.ws.rs.WebApplicationException 400 if parameters are invalid
     * @throws jakarta.ws.rs.WebApplicationException 500 if calculation fails
     */
    @GET
    @CacheResult(cacheName = "consultant-allocation")
    public Response getAllocation(
            @PathParam("userUuid") String userUuid,
            @QueryParam("startDate") String startDateStr,
            @QueryParam("endDate") String endDateStr) {

        try {
            log.infof("Fetching allocation for consultant: %s from %s to %s", userUuid, startDateStr, endDateStr);

            // Validate user UUID
            if (userUuid == null || userUuid.trim().isEmpty()) {
                log.warnf("Invalid user UUID: %s", userUuid);
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"User UUID cannot be null or empty\"}")
                    .build();
            }

            // Validate date parameters
            if (startDateStr == null || startDateStr.trim().isEmpty()) {
                log.warnf("Missing startDate parameter for user: %s", userUuid);
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"startDate query parameter is required (format: YYYY-MM-DD)\"}")
                    .build();
            }

            if (endDateStr == null || endDateStr.trim().isEmpty()) {
                log.warnf("Missing endDate parameter for user: %s", userUuid);
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"endDate query parameter is required (format: YYYY-MM-DD)\"}")
                    .build();
            }

            // Parse dates
            LocalDate startDate;
            LocalDate endDate;

            try {
                startDate = LocalDate.parse(startDateStr);
            } catch (DateTimeParseException e) {
                log.errorf(e, "Invalid startDate format: %s", startDateStr);
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Invalid startDate format. Expected ISO-8601 (YYYY-MM-DD), got: " + startDateStr + "\"}")
                    .build();
            }

            try {
                endDate = LocalDate.parse(endDateStr);
            } catch (DateTimeParseException e) {
                log.errorf(e, "Invalid endDate format: %s", endDateStr);
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Invalid endDate format. Expected ISO-8601 (YYYY-MM-DD), got: " + endDateStr + "\"}")
                    .build();
            }

            // Calculate allocation
            ConsultantAllocationDTO allocation = allocationService.calculateAllocation(userUuid, startDate, endDate);

            log.infof("Successfully calculated allocation for consultant %s: total=%d%%, over-allocated=%s",
                userUuid, allocation.getTotalAllocation(), allocation.isOverAllocated());

            return Response.ok(allocation).build();

        } catch (IllegalArgumentException e) {
            log.errorf(e, "Invalid parameters for consultant allocation: %s", userUuid);
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"" + e.getMessage() + "\"}")
                .build();

        } catch (Exception e) {
            log.errorf(e, "Failed to fetch allocation for consultant: %s", userUuid);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"Internal server error while calculating consultant allocation\"}")
                .build();
        }
    }
}
