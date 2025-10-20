package dk.trustworks.intranet.contracts.resources;

import dk.trustworks.intranet.contracts.dto.CreateRateAdjustmentRequest;
import dk.trustworks.intranet.contracts.dto.RateAdjustmentDTO;
import dk.trustworks.intranet.contracts.dto.UpdateRateAdjustmentRequest;
import dk.trustworks.intranet.contracts.services.ContractRateAdjustmentService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Tag(name = "Contract Rate Adjustments", description = "Manage rate adjustment rules for contract types")
@Path("/api/contract-types/{contractTypeCode}/rate-adjustments")
@JBossLog
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class ContractRateAdjustmentResource {

    @Inject
    ContractRateAdjustmentService rateAdjustmentService;

    @GET
    @Operation(summary = "List rate adjustments")
    public Response listAll(
            @PathParam("contractTypeCode") String contractTypeCode,
            @QueryParam("includeInactive") @DefaultValue("false") boolean includeInactive) {
        List<RateAdjustmentDTO> adjustments = rateAdjustmentService.listAll(contractTypeCode, includeInactive);
        return Response.ok(adjustments).build();
    }

    @GET
    @Path("/{ruleId}")
    @Operation(summary = "Get rate adjustment by ID")
    public Response getByRuleId(
            @PathParam("contractTypeCode") String contractTypeCode,
            @PathParam("ruleId") String ruleId) {
        RateAdjustmentDTO dto = rateAdjustmentService.findByRuleId(contractTypeCode, ruleId);
        return Response.ok(dto).build();
    }

    @POST
    @Operation(summary = "Create rate adjustment")
    public Response create(
            @PathParam("contractTypeCode") String contractTypeCode,
            @Valid CreateRateAdjustmentRequest request) {
        RateAdjustmentDTO created = rateAdjustmentService.create(contractTypeCode, request);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @Path("/{ruleId}")
    @Operation(summary = "Update rate adjustment")
    public Response update(
            @PathParam("contractTypeCode") String contractTypeCode,
            @PathParam("ruleId") String ruleId,
            @Valid UpdateRateAdjustmentRequest request) {
        RateAdjustmentDTO updated = rateAdjustmentService.update(contractTypeCode, ruleId, request);
        return Response.ok(updated).build();
    }

    @DELETE
    @Path("/{ruleId}")
    @Operation(summary = "Delete rate adjustment")
    public Response delete(
            @PathParam("contractTypeCode") String contractTypeCode,
            @PathParam("ruleId") String ruleId) {
        rateAdjustmentService.softDelete(contractTypeCode, ruleId);
        return Response.noContent().build();
    }

    @GET
    @Path("/calculate")
    @Operation(summary = "Calculate adjusted rate", description = "Preview rate calculation for a given base rate and date")
    public Response calculateRate(
            @PathParam("contractTypeCode") String contractTypeCode,
            @QueryParam("baseRate") @Parameter(description = "Base hourly rate") BigDecimal baseRate,
            @QueryParam("date") @Parameter(description = "Date to calculate for (YYYY-MM-DD)") LocalDate date) {
        if (baseRate == null || date == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("baseRate and date are required")
                    .build();
        }

        BigDecimal adjustedRate = rateAdjustmentService.getAdjustedRate(contractTypeCode, baseRate, date);
        return Response.ok(new RateCalculationResult(baseRate, adjustedRate, date)).build();
    }

    public static class RateCalculationResult {
        public BigDecimal baseRate;
        public BigDecimal adjustedRate;
        public LocalDate effectiveDate;

        public RateCalculationResult(BigDecimal baseRate, BigDecimal adjustedRate, LocalDate effectiveDate) {
            this.baseRate = baseRate;
            this.adjustedRate = adjustedRate;
            this.effectiveDate = effectiveDate;
        }
    }
}
