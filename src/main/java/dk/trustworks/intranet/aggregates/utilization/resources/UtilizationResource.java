package dk.trustworks.intranet.aggregates.utilization.resources;

import dk.trustworks.intranet.aggregates.availability.model.CompanyAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.aggregates.model.v2.CompanyBudgetPerMonth;
import dk.trustworks.intranet.aggregates.model.v2.CompanyWorkPerMonth;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.KeyDateValueListDTO;
import dk.trustworks.intranet.model.Company;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "Company Utilization")
@JBossLog
@Path("/company/{companyuuid}/utilization")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class UtilizationResource {

    @Inject
    BudgetService budgetService;

    @Inject
    AvailabilityService availabilityService;

    @Inject
    UtilizationService utilizationService;

    @PathParam("companyuuid")
    String companyuuid;

    @GET
    @Path("/budget")
    public List<DateValueDTO> getBudgetUtilizationPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate fromDate = dateIt(fromdate);
        LocalDate toDate = dateIt(todate);
        Company company = Company.findById(companyuuid);
        List<CompanyBudgetPerMonth> companyBudgetPerMonthList = budgetService.getCompanyBudgetsByPeriod(company, fromDate, toDate);

        List<CompanyAvailabilityPerMonth> dataPerMonthList = availabilityService.getCompanyAvailabilityByPeriod(company, fromDate, toDate);

        return dataPerMonthList.stream()
                .map(dataPerMonth -> new DateValueDTO(
                        LocalDate.of(dataPerMonth.getYear(), dataPerMonth.getMonth(), 1),
                        companyBudgetPerMonthList.stream()
                                .filter(workPerMonth -> workPerMonth.getYear() == dataPerMonth.getYear() && workPerMonth.getMonth() == dataPerMonth.getMonth())
                                .mapToDouble(CompanyBudgetPerMonth::getBudgetHours)
                                .sum() / dataPerMonth.getNetAvailableHours()
                ))
                .toList();
    }

    @GET
    @Path("/budget/teams/{teamuuid}")
    public List<DateValueDTO> getBudgetUtilizationPerMonthByTeam(
            @PathParam("teamuuid") String teamuuid,
            @QueryParam("fromdate") String fromdate,
            @QueryParam("todate") String todate) {
        return utilizationService.calculateTeamBudgetUtilization(teamuuid, dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/budget/employees")
    public List<KeyDateValueListDTO> getEmployeeBudgetUtilizationPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return utilizationService.calculateEmployeeBudgetUtilization(companyuuid, dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/actual")
    public List<DateValueDTO> getActualUtilizationPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate fromDate = dateIt(fromdate);
        LocalDate toDate = dateIt(todate);
        Company company = Company.findById(companyuuid);
        List<CompanyWorkPerMonth> workPerMonthList = CompanyWorkPerMonth.list("company = ?1 " +
                "AND ( " +
                "       ( year > " + fromDate.getYear() + ")  " +
                "    OR ( year = " + fromDate.getYear() + " AND  month >= " + fromDate.getMonthValue() + ") " +
                "  ) " +
                "  AND ( " +
                "       ( year < " + toDate.getYear() + ")  " +
                "    OR ( year = " + toDate.getYear() + " AND  month <= " + toDate.getMonthValue() + ") " +
                "  )", company);

        List<CompanyAvailabilityPerMonth> dataPerMonthList = availabilityService.getCompanyAvailabilityByPeriod(company, fromDate, toDate);

        return dataPerMonthList.stream()
                .map(dataPerMonth -> new DateValueDTO(
                        LocalDate.of(dataPerMonth.getYear(), dataPerMonth.getMonth(), 1),
                        workPerMonthList.stream()
                                .filter(workPerMonth -> workPerMonth.getYear() == dataPerMonth.getYear() && workPerMonth.getMonth() == dataPerMonth.getMonth())
                                .mapToDouble(CompanyWorkPerMonth::getWorkDuration)
                                .sum() / dataPerMonth.getNetAvailableHours()
                ))
                .toList();
    }

    @GET
    @Path("/actual/fiscalyear/{fiscalYear}")
    public DateValueDTO getActualUtilizationPerFiscalYear(@PathParam("fiscalYear") int fiscalYear) {
        LocalDate currentFiscalStartDate = LocalDate.of(fiscalYear, 7, 1);
        LocalDate currentFiscalEndDate = LocalDate.of(fiscalYear + 1, 7, 1);
        if (currentFiscalEndDate.isAfter(LocalDate.now())) currentFiscalEndDate = LocalDate.now().withDayOfMonth(1);
        return new DateValueDTO(currentFiscalStartDate, utilizationService.calculateCompanyActualUtilizationByPeriod(companyuuid, currentFiscalStartDate, currentFiscalEndDate));
    }

    @GET
    @Path("/actual/teams/{teamuuid}")
    public List<DateValueDTO> getActualUtilizationPerMonthByTeam(
            @PathParam("teamuuid") String teamuuid,
            @QueryParam("fromdate") String fromdate,
            @QueryParam("todate") String todate) {
        return utilizationService.calculateTeamActualUtilization(teamuuid, dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/actual/employees")
    public List<KeyDateValueListDTO> getEmployeeActualUtilizationPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return utilizationService.calculateEmployeeActualUtilization(companyuuid, dateIt(fromdate), dateIt(todate));
    }


    @GET
    @Path("/gross")
    public List<DateValueDTO> getGrossUtilizationPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate fromDate = dateIt(fromdate);
        LocalDate toDate = dateIt(todate);
        Company company = Company.findById(companyuuid);
        List<CompanyWorkPerMonth> workPerMonthList = CompanyWorkPerMonth.list("company = ?1 " +
                "AND ( " +
                "       ( year > " + fromDate.getYear() + ")  " +
                "    OR ( year = " + fromDate.getYear() + " AND  month >= " + fromDate.getMonthValue() + ") " +
                "  ) " +
                "  AND ( " +
                "       ( year < " + toDate.getYear() + ")  " +
                "    OR ( year = " + toDate.getYear() + " AND  month <= " + toDate.getMonthValue() + ") " +
                "  )", company);

        List<CompanyAvailabilityPerMonth> dataPerMonthList = availabilityService.getCompanyAvailabilityByPeriod(company, fromDate, toDate);

        return dataPerMonthList.stream()
                .map(dataPerMonth -> new DateValueDTO(
                        LocalDate.of(dataPerMonth.getYear(), dataPerMonth.getMonth(), 1),
                        workPerMonthList.stream()
                                .filter(workPerMonth -> workPerMonth.getYear() == dataPerMonth.getYear() && workPerMonth.getMonth() == dataPerMonth.getMonth())
                                .mapToDouble(CompanyWorkPerMonth::getWorkDuration)
                                .sum() / dataPerMonth.getGrossAvailableHours().doubleValue()
                ))
                .toList();
    }

}
