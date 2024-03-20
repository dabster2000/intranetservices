package dk.trustworks.intranet.aggregates.utilization.resources;

import dk.trustworks.intranet.aggregates.availability.model.CompanyAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.aggregates.model.v2.CompanyBudgetPerMonth;
import dk.trustworks.intranet.aggregates.model.v2.CompanyWorkPerMonth;
import dk.trustworks.intranet.aggregates.model.v2.EmployeeWorkPerMonth;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "utilization")
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

    @Inject
    EntityManager em;

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
    public List<DateValueDTO> getBudgetUtilizationPerMonthByTeam(@PathParam("teamuuid") String teamuuid) {
        LocalDate currentFiscalStartDate = DateUtils.getCurrentFiscalStartDate();
        List<DateValueDTO> utilizationPerMonth = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            LocalDate localDate = currentFiscalStartDate.plusMonths(i);

            String[] uuids = findUseruuidsPerTeam(teamuuid, localDate);
            System.out.println("uuids = '" + String.join("','", uuids) + "'");

            double availableHours = availabilityService.getSumOfAvailableHoursByUsersAndMonth(localDate, uuids);
            System.out.println("availableHours = " + availableHours);

            double budgetHours = ((Number) em.createNativeQuery("select sum(e.budgetHours) as value " +
                    "from bi_budget_per_day e " +
                    "where e.useruuid in ('" + String.join("','", uuids) + "') " +
                    "     AND e.year = " + localDate.getYear() + " " +
                    "     AND e.month = " + localDate.getMonthValue() + "; ").getResultList().stream().filter(Objects::nonNull).findAny().orElse(0.0)).doubleValue();

            System.out.println("budgetHours = " + budgetHours);

            utilizationPerMonth.add(new DateValueDTO(localDate, (budgetHours / availableHours)));
        }

        return utilizationPerMonth;
    }

    @GET
    @Path("/budget/users/{useruuid}")
    public List<DateValueDTO> getBudgetUtilizationPerMonthByConsultant(@PathParam("useruuid") String useruuid) {
        List<DateValueDTO> availabilityPerMonth = ((List<Tuple>) em.createNativeQuery("select " +
                "    cast(concat(e.year,'-',e.month,'-01') as date) as date, " +
                "    (sum(e.gross_available_hours - e.paid_leave_hours - e.non_payd_leave_hours - e.maternity_leave_hours - e.sick_hours - e.vacation_hours - e.unavailable_hours)) as value " +
                "from " +
                "    bi_availability_per_day e " +
                "     WHERE consultant_type = 'CONSULTANT' " +
                "     AND status_type = 'ACTIVE' " +
                "     AND useruuid = '"+useruuid+"' " +
                "group by e.year, e.month;", Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        ((Date) tuple.get("date")).toLocalDate(),
                        ((BigDecimal) tuple.get("value")).doubleValue()
                ))
                .toList();
        List<DateValueDTO> budgetsPerMonth = ((List<Tuple>) em.createNativeQuery("select " +
                "    b.document_date as date, (sum(b.budgetHours)) as value " +
                "from " +
                "    bi_budget_per_day b " +
                "    WHERE useruuid = '"+useruuid+"' " +
                "group by " +
                "    b.month;", Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        ((Date) tuple.get("date")).toLocalDate(),
                        (Double) tuple.get("value")
                ))
                .toList();
        return availabilityPerMonth
                .stream()
                .peek(availability -> budgetsPerMonth.stream()
                        .filter(budget -> budget.getDate().equals(availability.getDate()))
                        .findFirst()
                        .ifPresentOrElse(bud -> availability.setValue(bud.getValue() / availability.getValue()), () -> availability.setValue(0.0)))
                .toList();
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
    @Path("/actual/teams/{teamuuid}")
    public List<DateValueDTO> getActualUtilizationPerMonthByTeam(@PathParam("teamuuid") String teamuuid) {;
        LocalDate currentFiscalStartDate = DateUtils.getCurrentFiscalStartDate();
        List<DateValueDTO> utilizationPerMonth = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            LocalDate localDate = currentFiscalStartDate.plusMonths(i);
            String[] uuids = findUseruuidsPerTeam(teamuuid, localDate);

            double billableHours = ((Number) em.createNativeQuery("select sum(wf.workduration) " +
                    "from work_full wf " +
                    "where " +
                    "    wf.registered >= :startDate and " +
                    "    wf.registered < :endDate and " +
                    "    wf.useruuid in ('" + String.join("','", uuids) + "') and " +
                    "    wf.rate > 0 " +
                    "group by month(registered), year(registered)")
                    .setParameter("startDate", localDate)
                    .setParameter("endDate", localDate.plusMonths(1))
                    .getResultList().stream().filter(Objects::nonNull).findAny().orElse(0.0)).doubleValue();

            double availableHours = availabilityService.getSumOfAvailableHoursByUsersAndMonth(localDate, uuids);

            utilizationPerMonth.add(new DateValueDTO(localDate, (billableHours / availableHours)));
        }
        return utilizationPerMonth;
    }


    private String[] findUseruuidsPerTeam(String teamuuid, LocalDate date) {
        return ((List<Tuple>) em.createNativeQuery("select " +
                "    t.useruuid as useruuid " +
                "from " +
                "    teamroles as t " +
                "where " +
                "    t.teamuuid = '"+teamuuid+"' and " +
                "    t.membertype = 'MEMBER' and " +
                "    t.startdate <= '"+ DateUtils.stringIt(date) +"' and " +
                "    (t.enddate is null or t.enddate > '"+DateUtils.stringIt(date)+"')", Tuple.class).getResultList()).stream()
                .map(tuple -> new String(
                        ((String) tuple.get("useruuid"))
                ))
                .toArray(String[]::new);
    }

    @GET
    @Path("/actual/users/{useruuid}")
    public List<DateValueDTO> getActualUtilizationPerMonthByConsultant(@PathParam("useruuid") String useruuid) {
        List<EmployeeWorkPerMonth> employeeWorkPerMonthList = EmployeeWorkPerMonth.list("useruuid = ?1", useruuid);
        List<EmployeeAvailabilityPerMonth> employeeAvailabilityList = availabilityService.getEmployeeAvailability(User.findById(useruuid));
        return employeeAvailabilityList
                .<EmployeeAvailabilityPerMonth>stream()
                .map(edpm -> new DateValueDTO(
                        LocalDate.of(edpm.getYear(), edpm.getMonth(), 1),
                        employeeWorkPerMonthList.stream().filter(ewpm -> ewpm.getYear() == edpm.getYear() && ewpm.getMonth() == edpm.getMonth()).findAny().orElse(new EmployeeWorkPerMonth()).getWorkDuration() / edpm.getNetAvailableHours() * 100.0
                ))
                .toList();
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
