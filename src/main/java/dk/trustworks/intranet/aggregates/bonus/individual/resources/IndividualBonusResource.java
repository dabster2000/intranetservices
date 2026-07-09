package dk.trustworks.intranet.aggregates.bonus.individual.resources;

import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.ProjectedPayoutDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.model.ProjectedPayout;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusPayoutService;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusScheduleService;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST boundary for individual bonus rules and their projected/materialised payouts. Thin — all
 * business logic lives in the injected services. Rule authoring is behind {@code bonus:write};
 * reads/projection behind {@code bonus:read}.
 */
@JBossLog
@Tag(name = "bonus")
@Path("/individual-bonuses")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"bonus:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class IndividualBonusResource {

    @Inject IndividualBonusService bonusService;
    @Inject IndividualBonusScheduleService scheduleService;
    @Inject IndividualBonusPayoutService payoutService;

    @GET
    public List<IndividualBonusRuleDTO> list(@QueryParam("userUuid") String userUuid) {
        return bonusService.listByUser(userUuid);
    }

    @POST
    @RolesAllowed({"bonus:write"})
    public Response create(@Valid IndividualBonusRuleRequest request) {
        IndividualBonusRuleDTO created = bonusService.create(request);
        return Response.created(URI.create("/individual-bonuses/" + created.uuid()))
                .entity(created)
                .build();
    }

    @PUT
    @Path("/{uuid}")
    @RolesAllowed({"bonus:write"})
    public IndividualBonusRuleDTO update(@PathParam("uuid") String uuid,
                                         @Valid IndividualBonusRuleRequest request) {
        return bonusService.update(uuid, request);
    }

    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"bonus:write"})
    public Response delete(@PathParam("uuid") String uuid) {
        bonusService.delete(uuid);
        return Response.noContent().build();
    }

    /**
     * Read-time projection of upcoming (and committed) payouts for a user over the horizon.
     * Nothing is materialised.
     */
    @GET
    @Path("/projection")
    public List<ProjectedPayoutDTO> projection(@QueryParam("userUuid") String userUuid,
                                               @QueryParam("horizonMonths") @DefaultValue("24") int horizonMonths) {
        if (userUuid == null || userUuid.isBlank()) {
            throw new BadRequestException("userUuid is required");
        }
        LocalDate horizonEnd = LocalDate.now().plusMonths(Math.max(1, horizonMonths)).withDayOfMonth(1);
        return scheduleService.project(userUuid, horizonEnd).stream().map(this::toDTO).toList();
    }

    /** Materialise all payouts due in the given month (default: current month). Idempotent. */
    @POST
    @Path("/payouts/run")
    @RolesAllowed({"bonus:write"})
    public Response runPayouts(@QueryParam("month") String month) {
        LocalDate payMonth = parseMonth(month);
        int created = payoutService.materializeDue(payMonth);
        return Response.ok(Map.of("month", payMonth.toString(), "created", created)).build();
    }

    private LocalDate parseMonth(String month) {
        if (month == null || month.isBlank()) return LocalDate.now().withDayOfMonth(1);
        try {
            return LocalDate.parse(month).withDayOfMonth(1);
        } catch (RuntimeException e) {
            throw new BadRequestException("month must be an ISO date (yyyy-MM-01)");
        }
    }

    private ProjectedPayoutDTO toDTO(ProjectedPayout p) {
        return new ProjectedPayoutDTO(p.month(), p.amount(), p.kind().name(), p.status().name(),
                p.sourceReference(), p.estimated(), p.truncatedByTermination());
    }
}
