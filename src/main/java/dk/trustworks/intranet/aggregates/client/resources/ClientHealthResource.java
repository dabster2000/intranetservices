package dk.trustworks.intranet.aggregates.client.resources;

import dk.trustworks.intranet.aggregates.client.services.ClientHealthService;
import dk.trustworks.intranet.dto.contracts.ClientHealthDTO;
import io.quarkus.cache.CacheResult;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST resource for client health status.
 *
 * <p>This resource provides a single endpoint to retrieve comprehensive health metrics for clients,
 * including contract status counts and overdue invoice information.
 *
 * <p><b>Endpoints:</b>
 * <ul>
 *   <li>GET /api/clients/{clientUuid}/health - Get client health status</li>
 * </ul>
 *
 * <p><b>Caching:</b>
 * <ul>
 *   <li>Cache name: "client-health"</li>
 *   <li>TTL: 1 hour (configured in application.yml)</li>
 *   <li>Key: clientUuid path parameter</li>
 * </ul>
 *
 * <p><b>Health Status Values:</b>
 * <ul>
 *   <li>HEALTHY: Active contracts exist AND no overdue invoices</li>
 *   <li>AT_RISK: No active contracts BUT budget contracts exist, OR 1-2 overdue invoices</li>
 *   <li>CRITICAL: No active/budget contracts OR 3+ overdue invoices</li>
 * </ul>
 *
 * <p><b>Example Response:</b>
 * <pre>
 * {
 *   "clientUuid": "abc-123",
 *   "health": "HEALTHY",
 *   "activeCount": 3,
 *   "budgetCount": 1,
 *   "expiredCount": 0,
 *   "pausedCount": 0,
 *   "completedCount": 2,
 *   "overdueInvoicesCount": 0,
 *   "overdueAmount": 0.0
 * }
 * </pre>
 *
 * @see ClientHealthService
 * @see ClientHealthDTO
 * @since 1.0
 */
@Tag(name = "Client Health")
@Path("/api/clients/{clientUuid}/health")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
@JBossLog
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class ClientHealthResource {

    @Inject
    ClientHealthService clientHealthService;

    /**
     * Get comprehensive health status for a client.
     *
     * <p>This endpoint calculates and returns client health metrics including:
     * <ul>
     *   <li>Overall health status (HEALTHY, AT_RISK, or CRITICAL)</li>
     *   <li>Contract counts by status (active, budget, expired, paused, completed)</li>
     *   <li>Overdue invoice statistics (count and total amount)</li>
     * </ul>
     *
     * <p><b>Performance:</b>
     * <ul>
     *   <li>First request: ~50-100ms (6 database queries)</li>
     *   <li>Cached requests: ~1-5ms (served from cache)</li>
     *   <li>Cache TTL: 1 hour</li>
     * </ul>
     *
     * <p><b>Error Handling:</b>
     * <ul>
     *   <li>400 Bad Request: Invalid or missing client UUID</li>
     *   <li>500 Internal Server Error: Database or service errors</li>
     * </ul>
     *
     * @param clientUuid Client UUID (path parameter)
     * @return ClientHealthDTO with health status and metrics
     * @throws jakarta.ws.rs.WebApplicationException 400 if clientUuid is invalid
     * @throws jakarta.ws.rs.WebApplicationException 500 if calculation fails
     */
    @GET
    @CacheResult(cacheName = "client-health")
    public Response getHealth(@PathParam("clientUuid") String clientUuid) {
        try {
            log.infof("Fetching health for client: %s", clientUuid);

            // Validate client UUID
            if (clientUuid == null || clientUuid.trim().isEmpty()) {
                log.warnf("Invalid client UUID: %s", clientUuid);
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Client UUID cannot be null or empty\"}")
                    .build();
            }

            // Calculate health status
            ClientHealthDTO health = clientHealthService.calculateHealth(clientUuid);

            log.infof("Successfully calculated health for client %s: %s", clientUuid, health.getHealth());

            return Response.ok(health).build();

        } catch (IllegalArgumentException e) {
            log.errorf(e, "Invalid parameters for client health: %s", clientUuid);
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"" + e.getMessage() + "\"}")
                .build();

        } catch (Exception e) {
            log.errorf(e, "Failed to fetch health for client: %s", clientUuid);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"Internal server error while calculating client health\"}")
                .build();
        }
    }
}
