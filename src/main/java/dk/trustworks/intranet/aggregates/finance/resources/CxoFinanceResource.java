package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.dto.MonthlyRevenueMarginDTO;
import dk.trustworks.intranet.aggregates.finance.services.CxoFinanceService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST API for CxO dashboard finance data.
 * Provides aggregated revenue and margin trends for executive dashboards.
 */
@JBossLog
@Tag(name = "finance")
@Path("/finance/cxo")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class CxoFinanceResource {

    @Inject
    CxoFinanceService cxoFinanceService;

    /**
     * Gets monthly revenue and margin trend data.
     *
     * @param fromDate Start date (ISO-8601 format, optional)
     * @param toDate End date (ISO-8601 format, optional)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional)
     * @return List of monthly revenue and margin data points
     */
    @GET
    @Path("/revenue-margin-trend")
    public List<MonthlyRevenueMarginDTO> getRevenueMarginTrend(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId) {

        log.debugf("GET /finance/cxo/revenue-margin-trend: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s",
                fromDate, toDate, sectors, serviceLines, contractTypes, clientId);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);

        // Call service layer
        List<MonthlyRevenueMarginDTO> result = cxoFinanceService.getRevenueMarginTrend(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId
        );

        log.debugf("Returning %d data points", result.size());
        return result;
    }

    /**
     * Parses comma-separated string into a Set of trimmed values.
     * Returns null if input is null or empty.
     */
    private Set<String> parseCommaSeparated(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Set<String> result = new HashSet<>();
        for (String value : input.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? null : result;
    }
}
