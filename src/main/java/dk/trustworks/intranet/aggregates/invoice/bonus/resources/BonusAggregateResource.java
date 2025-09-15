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
import java.util.stream.Collectors;

/**
 * Resource for aggregate bonus operations across multiple invoices.
 * Provides company-level and financial year-level aggregations.
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

    /**
     * DTO for company-specific bonus amount.
     */
    public record CompanyAmount(
            @Schema(description = "Company UUID", example = "d8894494-2fb4-4f72-9e05-e6032e6dd691")
            String companyId,
            @Schema(description = "Company name", example = "Trustworks")
            String companyName,
            @Schema(description = "Total bonus amount for this company", example = "100000.00")
            double amount
    ) {}

    /**
     * DTO for user's bonus share across companies.
     */
    public record UserCompanyShare(
            @Schema(description = "User UUID", example = "11111111-1111-1111-1111-111111111111")
            String userId,
            @Schema(description = "User full name", example = "John Doe")
            String userName,
            @Schema(description = "Total bonus amount across all companies", example = "150000.00")
            double totalAmount,
            @Schema(description = "Breakdown by company")
            List<CompanyAmount> companyAmounts
    ) {}

    /**
     * Main response DTO for company bonus share by financial year.
     */
    public record CompanyBonusShareResponse(
            @Schema(description = "Financial year (starting year)", example = "2025")
            int financialYear,
            @Schema(description = "Period start date (inclusive)", example = "2025-07-01")
            LocalDate periodStart,
            @Schema(description = "Period end date (inclusive)", example = "2026-06-30")
            LocalDate periodEnd,
            @Schema(description = "Total approved bonus amount for all users", example = "500000.00")
            double totalAmount,
            @Schema(description = "Per-user breakdown with company splits")
            List<UserCompanyShare> userShares
    ) {}

    @GET
    @Path("/company-share")
    @Operation(
            summary = "Get company bonus share by financial year",
            description = """
                    Calculates approved bonus amounts per user, split by their company affiliation
                    at the time of each invoice. The financial year runs from July 1 to June 30.
                    Company association is determined from UserStatus at invoice date.
                    Only approved bonuses are included in the calculation.
                    """
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "OK – Company bonus share data",
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
                                              "totalAmount": 500000.00,
                                              "userShares": [
                                                {
                                                  "userId": "11111111-1111-1111-1111-111111111111",
                                                  "userName": "John Doe",
                                                  "totalAmount": 150000.00,
                                                  "companyAmounts": [
                                                    {
                                                      "companyId": "d8894494-2fb4-4f72-9e05-e6032e6dd691",
                                                      "companyName": "Trustworks",
                                                      "amount": 100000.00
                                                    },
                                                    {
                                                      "companyId": "22222222-2222-2222-2222-222222222222",
                                                      "companyName": "ClientCo",
                                                      "amount": 50000.00
                                                    }
                                                  ]
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            ),
            @APIResponse(responseCode = "400", description = "Bad request – invalid financial year"),
            @APIResponse(responseCode = "401", description = "Unauthorized"),
            @APIResponse(responseCode = "403", description = "Forbidden – insufficient permissions")
    })
    public CompanyBonusShareResponse getCompanyBonusShareByFinancialYear(
            @Parameter(
                    name = "financialYear",
                    description = "Financial year starting year (FY YYYY runs from YYYY-07-01 to (YYYY+1)-06-30)",
                    required = true,
                    example = "2025"
            )
            @QueryParam("financialYear") Integer financialYear) {

        // Validate financial year
        if (financialYear == null) {
            throw new BadRequestException("financialYear parameter is required");
        }
        if (financialYear < 2000 || financialYear > 2999) {
            throw new BadRequestException("financialYear must be between 2000 and 2999");
        }

        // Calculate the financial year period
        LocalDate periodStart = LocalDate.of(financialYear, 7, 1);
        LocalDate periodEnd = LocalDate.of(financialYear + 1, 6, 30);

        // Call service method to calculate company bonus shares
        Map<String, List<CompanyAmount>> userCompanyShares =
                bonusService.calculateCompanyBonusShareByFinancialYear(financialYear, periodStart, periodEnd);

        // Transform the service result into response DTOs
        List<UserCompanyShare> userShares = userCompanyShares.entrySet().stream()
                .map(entry -> {
                    String userId = entry.getKey();
                    List<CompanyAmount> companyAmounts = entry.getValue();
                    double userTotal = companyAmounts.stream()
                            .mapToDouble(CompanyAmount::amount)
                            .sum();

                    // Get user name from service (will be implemented)
                    String userName = bonusService.getUserFullName(userId);

                    return new UserCompanyShare(userId, userName, userTotal, companyAmounts);
                })
                .sorted((a, b) -> Double.compare(b.totalAmount(), a.totalAmount())) // Sort by total descending
                .toList();

        // Calculate grand total
        double grandTotal = userShares.stream()
                .mapToDouble(UserCompanyShare::totalAmount)
                .sum();

        return new CompanyBonusShareResponse(
                financialYear,
                periodStart,
                periodEnd,
                grandTotal,
                userShares
        );
    }
}