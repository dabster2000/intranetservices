package dk.trustworks.intranet.aggregates.invoice.bonus.resources;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibility;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibilityGroup;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Tag(name = "invoice-eligibility-groups", description = "Manage groups of bonus eligibility entries")
@Path("/invoices/eligibility-groups")
@RequestScoped
@SecurityRequirement(name = "jwt")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
public class BonusEligibilityGroupResource {

    public record GroupDTO(
            @Schema(description = "Group name", example = "FY2025") String name,
            @Schema(description = "Financial year starting year (FY YYYY runs from YYYY-07-01 to (YYYY+1)-06-30)", example = "2025") Integer financialYear
    ) {}

    /** Response DTO for the approved total endpoint. */
    public record ApprovedTotalDTO(
            String groupuuid,
            LocalDate from,
            LocalDate to,
            double approvedTotal
    ) {}

    @Inject InvoiceBonusService bonusService;
    @Inject EntityManager em;

    @GET
    @Operation(summary = "List groups", description = "List all bonus eligibility groups")
    public List<BonusEligibilityGroup> list() {
        return BonusEligibilityGroup.listAll();
    }

    @GET
    @Path("/{uuid}")
    @Operation(summary = "Get group", description = "Get a single group by UUID")
    @APIResponse(responseCode = "404", description = "Group not found")
    public BonusEligibilityGroup get(@PathParam("uuid") String uuid) {
        BonusEligibilityGroup g = BonusEligibilityGroup.findById(uuid);
        if (g == null) throw new NotFoundException();
        return g;
    }

    @POST
    @Transactional
    @Operation(summary = "Create group", description = "Create a new bonus eligibility group")
    @APIResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = BonusEligibilityGroup.class),
                    examples = @ExampleObject(value = "{\n  \"uuid\": \"generated\",\n  \"name\": \"FY2025\",\n  \"financialYear\": 2025\n}")))
    public Response create(GroupDTO dto) {
        validate(dto);
        BonusEligibilityGroup g = new BonusEligibilityGroup();
        g.setName(dto.name());
        g.setFinancialYear(dto.financialYear());
        g.persist();
        return Response.status(Response.Status.CREATED).entity(g).build();
    }

    @PUT
    @Path("/{uuid}")
    @Transactional
    @Operation(summary = "Update group", description = "Update name and financial year of an existing group")
    public BonusEligibilityGroup update(@PathParam("uuid") String uuid, GroupDTO dto) {
        validate(dto);
        BonusEligibilityGroup g = BonusEligibilityGroup.findById(uuid);
        if (g == null) throw new NotFoundException();
        g.setName(dto.name());
        g.setFinancialYear(dto.financialYear());
        g.persist();
        return g;
    }

    @DELETE
    @Path("/{uuid}")
    @Transactional
    @Operation(summary = "Delete group", description = "Delete a group. Existing eligibility rows will have group set to NULL (FK ON DELETE SET NULL)")
    public void delete(@PathParam("uuid") String uuid) {
        BonusEligibilityGroup g = BonusEligibilityGroup.findById(uuid);
        if (g == null) throw new NotFoundException();
        g.delete();
    }

    /**
     * --- NEW (helper) ---
     * Computes the sum of approved bonus totals for all invoices that:
     *  - have at least one APPROVED InvoiceBonus for a user in the given group, and
     *  - have invoice date within [periodStart; periodEnd] (inclusive).
     *
     * Reuses {@link InvoiceBonusService#sumApproved(String)} to get the per-invoice approved total.
     */
    public double sumApprovedForGroupPeriod(BonusEligibilityGroup group, LocalDate periodStart, LocalDate periodEnd) {
        if (group == null) throw new NotFoundException("Group not found");
        if (periodStart == null || periodEnd == null || periodStart.isAfter(periodEnd)) {
            throw new BadRequestException("Invalid period");
        }

        // Collect user UUIDs in the group
        List<BonusEligibility> eligibility = BonusEligibility
                .<BonusEligibility>list("group.uuid = ?1", group.getUuid());
        if (eligibility.isEmpty()) return 0.0;

        Set<String> users = new HashSet<>();
        for (BonusEligibility be : eligibility) {
            if (be.getUseruuid() != null && !be.getUseruuid().isBlank()) {
                users.add(be.getUseruuid());
            }
        }
        if (users.isEmpty()) return 0.0;

        // Find invoice UUIDs that have at least one APPROVED bonus for a group member within the period
        List<String> invoiceIds = em.createQuery("""
                SELECT DISTINCT i.uuid
                FROM dk.trustworks.intranet.aggregates.invoice.model.Invoice i,
                     dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b
                WHERE b.invoiceuuid = i.uuid
                  AND b.status = :approved
                  AND b.useruuid IN :users
                  AND i.invoicedate >= :from
                  AND i.invoicedate <= :to
            """, String.class)
                .setParameter("approved", SalesApprovalStatus.APPROVED)
                .setParameter("users", users)
                .setParameter("from", periodStart)
                .setParameter("to", periodEnd)
                .getResultList();

        if (invoiceIds.isEmpty()) return 0.0;

        // Reuse existing code to get the per-invoice approved total, then sum
        double total = 0.0;
        for (String invId : invoiceIds) {
            total += bonusService.sumApproved(invId); // <-- reuse
        }
        return round2(total);
    }

    /**
     * --- UPDATED (endpoint) ---
     * Convenience endpoint wrapping {@link #sumApprovedForGroupPeriod(BonusEligibilityGroup, LocalDate, LocalDate)}.
     * Takes a financialYear (YYYY). If omitted, the group's own financialYear is used.
     */
    @GET
    @Path("/{uuid}/approved-total")
    @Operation(
            summary = "Sum approved bonuses for a group in a financial year",
            description = "Returns the sum of per-invoice APPROVED bonus totals for all invoices that have at least one approved bonus by a member of the group within the specified financial year (July 1 to June 30). If financialYear is not provided, the group's financialYear is used."
    )
    public ApprovedTotalDTO approvedTotalEndpoint(@PathParam("uuid") String uuid,
                                                  @QueryParam("financialYear") Integer financialYear) {
        BonusEligibilityGroup g = BonusEligibilityGroup.findById(uuid);
        if (g == null) throw new NotFoundException();

        int fy = (financialYear != null) ? financialYear : g.getFinancialYear();
        if (fy < 2000 || fy > 2999) throw new BadRequestException("financialYear must be between 2000 and 2999");
        LocalDate start = LocalDate.of(fy, 7, 1);
        LocalDate end   = LocalDate.of(fy + 1, 6, 30);

        double total = sumApprovedForGroupPeriod(g, start, end);
        return new ApprovedTotalDTO(g.getUuid(), start, end, total);
    }

    // ------------------------- helpers -------------------------

    private static void validate(GroupDTO dto) {
        if (dto == null) throw new BadRequestException("Body required");
        if (dto.name() == null || dto.name().isBlank()) throw new BadRequestException("name is required");
        if (dto.financialYear() == null) throw new BadRequestException("financialYear is required");
        if (dto.financialYear() < 2000 || dto.financialYear() > 2999) throw new BadRequestException("financialYear must be between 2000 and 2999");
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
