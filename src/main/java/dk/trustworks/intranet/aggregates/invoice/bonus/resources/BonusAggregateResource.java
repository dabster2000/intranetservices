package dk.trustworks.intranet.aggregates.invoice.bonus.resources;

import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Aggregate bonus operations and reporting.
 *
 * Path kept as /invoices/bonuses for compatibility with existing clients.
 */
@Tag(name = "invoice-bonus-aggregates", description = "Aggregate bonus operations and reporting")
@Path("/invoices/bonuses")
@RequestScoped
@SecurityRequirement(name = "jwt")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM", "FINANCE", "ADMIN"})
public class BonusAggregateResource {

    @Inject
    InvoiceBonusService bonusService;

    /** DTO for company‑specific amount. */
    public record CompanyAmount(
            @Schema(description = "Company UUID") String companyId,
            @Schema(description = "Company name") String companyName,
            @Schema(description = "Sales amount attributed to this company") double amount
    ) {}

    /** DTO for a single user's split across companies. */
    public record UserCompanyShare(
            @Schema(description = "User UUID") String userId,
            @Schema(description = "User full name") String userName,
            @Schema(description = "Sum across all companies") double totalAmount,
            @Schema(description = "Per‑company breakdown") List<CompanyAmount> companyAmounts
    ) {}

    /** Response DTO. */
    public record CompanyBonusShareResponse(
            @Schema(description = "Financial year (starting year)") int financialYear,
            @Schema(description = "Period start (inclusive)") LocalDate periodStart,
            @Schema(description = "Period end (inclusive)") LocalDate periodEnd,
            @Schema(description = "Grand total across all users") double totalAmount,
            @Schema(description = "User‑level breakdown") List<UserCompanyShare> userShares
    ) {}

    @GET
    @Path("/company-share")
    @Operation(
            summary = "Company split of approved bonus sales by financial year",
            description = """
                Splits each user's **approved bonus sales amount** by the **company of the consultant
                on each selected invoice line**, using the user's company **at the invoice date** (UserStatus).
                The financial year runs July 1 to June 30. Only APPROVED bonuses are included.
                """
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "OK",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CompanyBonusShareResponse.class),
                            examples = @ExampleObject(
                                    name = "Example",
                                    value = """
                                            {
                                              "financialYear": 2025,
                                              "periodStart": "2025-07-01",
                                              "periodEnd": "2026-06-30",
                                              "totalAmount": 123456.78,
                                              "userShares": [
                                                {
                                                  "userId": "11111111-1111-1111-1111-111111111111",
                                                  "userName": "Jane Doe",
                                                  "totalAmount": 45678.90,
                                                  "companyAmounts": [
                                                    { "companyId": "A", "companyName": "Trustworks", "amount": 30000.00 },
                                                    { "companyId": "B", "companyName": "Tech", "amount": 15678.90 }
                                                  ]
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            ),
            @APIResponse(responseCode = "400", description = "Bad request – invalid financial year")
    })
    public CompanyBonusShareResponse companyBonusShareByFinancialYear(
            @Parameter(
                    name = "financialYear",
                    description = "FY starting year (FY YYYY runs from YYYY-07-01 to (YYYY+1)-06-30)",
                    required = true
            )
            @QueryParam("financialYear") Integer financialYear) {

        if (financialYear == null) {
            throw new BadRequestException("financialYear parameter is required");
        }
        if (financialYear < 2000 || financialYear > 2999) {
            throw new BadRequestException("financialYear must be between 2000 and 2999");
        }

        LocalDate periodStart = LocalDate.of(financialYear, 7, 1);
        LocalDate periodEnd   = LocalDate.of(financialYear + 1, 6, 30);

        // Compute (user -> list of company amounts)
        Map<String, List<CompanyAmount>> userCompanyShares =
                bonusService.calculateCompanyBonusShareByFinancialYear(financialYear, periodStart, periodEnd);

        // Map to response with user names and totals
        List<UserCompanyShare> userShares = userCompanyShares.entrySet().stream()
                .map(e -> {
                    String userId = e.getKey();
                    List<CompanyAmount> companyAmounts = e.getValue();
                    double userTotal = companyAmounts.stream().mapToDouble(CompanyAmount::amount).sum();
                    String userName = bonusService.getUserFullName(userId);
                    return new UserCompanyShare(userId, userName, userTotal, companyAmounts);
                })
                .sorted((a, b) -> Double.compare(b.totalAmount(), a.totalAmount()))
                .toList();

        double grandTotal = userShares.stream().mapToDouble(UserCompanyShare::totalAmount).sum();

        return new CompanyBonusShareResponse(financialYear, periodStart, periodEnd, grandTotal, userShares);
    }
}
