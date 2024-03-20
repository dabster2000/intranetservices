package dk.trustworks.intranet.aggregates.budgets.resources;

import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.resources.CompanyResource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;

@Tag(name = "Company Budgets")
@Path("/companies")
@JBossLog
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class CompanyBudgetResource {

    @Inject
    BudgetService budgetService;

    @Inject
    CompanyResource companyResource;

    @GET
    @Path("/budgets/amount")
    public List<DateValueDTO> getBudgetAmountsPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        List<DateValueDTO> budgetRevenueByPeriod = new ArrayList<>();
        companyResource.findAllCompanies().forEach(company -> {
            budgetRevenueByPeriod.addAll(budgetService.getCompanyBudgetAmountByPeriod(company.getUuid(), dateIt(fromdate), dateIt(todate)));
        });
        return budgetRevenueByPeriod;
    }

    @GET
    @Path("/{companyuuid}/budgets/amount")
    public List<DateValueDTO> getBudgetRevenueByPeriod(@PathParam("companyuuid") String companyuuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return budgetService.getCompanyBudgetAmountByPeriod(companyuuid, dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/{companyuuid}/budgets/amount/months/{month}")
    public DateValueDTO getBudgetRevenueForSingleMonth(@PathParam("companyuuid") String companyuuid, @PathParam("month") String month) {
        return budgetService.getCompanyBudgetAmountForSingleMonth(companyuuid, dateIt(month));
    }
}