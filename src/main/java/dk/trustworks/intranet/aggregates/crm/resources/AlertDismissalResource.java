package dk.trustworks.intranet.aggregates.crm.resources;

import dk.trustworks.intranet.aggregates.crm.model.AlertDismissal;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Tag(name = "Alert Dismissals")
@JBossLog
@Path("/alert-dismissals")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"crm:read"})
public class AlertDismissalResource {

    @GET
    public List<String> listDismissedAlertIds(@QueryParam("userUuid") String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            throw new BadRequestException("userUuid query parameter is required");
        }
        return AlertDismissal.findByUserUuid(userUuid)
                .stream()
                .map(d -> d.alertId)
                .toList();
    }

    @POST
    @Transactional
    @RolesAllowed({"crm:write"})
    public Response upsertDismissal(DismissalRequest request) {
        if (request == null || request.userUuid() == null || request.alertId() == null) {
            throw new BadRequestException("userUuid and alertId are required");
        }
        var existing = AlertDismissal.findByUserUuidAndAlertId(request.userUuid(), request.alertId());
        if (existing != null) {
            existing.expiresAt = request.expiresAt();
            existing.dismissedAt = LocalDateTime.now();
        } else {
            var dismissal = new AlertDismissal(
                    UUID.randomUUID().toString(),
                    request.userUuid(),
                    request.alertId(),
                    LocalDateTime.now(),
                    request.expiresAt()
            );
            dismissal.persist();
        }
        return Response.ok().build();
    }

    @DELETE
    @Path("/{alertId}")
    @Transactional
    @RolesAllowed({"crm:write"})
    public Response deleteDismissal(@PathParam("alertId") String alertId,
                                    @QueryParam("userUuid") String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            throw new BadRequestException("userUuid query parameter is required");
        }
        AlertDismissal.deleteByUserUuidAndAlertId(userUuid, alertId);
        return Response.noContent().build();
    }

    public record DismissalRequest(String userUuid, String alertId, LocalDateTime expiresAt) {}
}
