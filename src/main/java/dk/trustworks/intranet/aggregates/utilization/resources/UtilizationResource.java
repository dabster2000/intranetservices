package dk.trustworks.intranet.aggregates.utilization.resources;

import dk.trustworks.intranet.aggregates.availability.model.CompanyAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.aggregates.model.v2.CompanyBudgetPerMonth;
import dk.trustworks.intranet.aggregates.model.v2.CompanyWorkPerMonth;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.KeyDateValueListDTO;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    @Inject
    WorkService workService;

    @PathParam("companyuuid")
    String companyuuid;

    @Inject
    EntityManager em;
    @Inject
    UserService userService;

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
    public List<DateValueDTO> getBudgetUtilizationPerMonthByTeam(@PathParam("teamuuid") String teamuuid, @QueryParam("fiscalYear") int fiscalYear) {
        LocalDate currentFiscalStartDate = LocalDate.of(fiscalYear, 7, 1);
        List<DateValueDTO> utilizationPerMonth = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            LocalDate localDate = currentFiscalStartDate.plusMonths(i);

            String[] uuids = findUseruuidsPerTeam(teamuuid, localDate);

            double availableHours = availabilityService.getSumOfAvailableHoursByUsersAndMonth(localDate, uuids);

            double budgetHours = ((Number) em.createNativeQuery(
                    "SELECT SUM(e.budgetHours) AS value " +
                    "FROM bi_budget_per_day e " +
                    "WHERE e.useruuid IN :uuids " +
                    "  AND e.year = :year " +
                    "  AND e.month = :month")
                    .setParameter("uuids", List.of(uuids))
                    .setParameter("year", localDate.getYear())
                    .setParameter("month", localDate.getMonthValue())
                    .getResultList().stream().filter(Objects::nonNull).findAny().orElse(0.0)).doubleValue();

            utilizationPerMonth.add(new DateValueDTO(localDate, (budgetHours / availableHours)));
        }

        return utilizationPerMonth;
    }

    @GET
    @Path("/budget/employees")
    public List<KeyDateValueListDTO> getEmployeeBudgetUtilizationPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate fromDate = dateIt(fromdate);
        LocalDate toDate = dateIt(todate);

        // List to store results
        List<KeyDateValueListDTO> employeeUtilizationList = new ArrayList<>();

        // Get all employees (this might come from the UserService or a different service)
        List<User> users = userService.findCurrentlyEmployedUsers(false, ConsultantType.CONSULTANT).stream().filter(u -> u.getUserStatus(toDate).getCompany().getUuid().equals(companyuuid)).toList();

        for (User user : users) {
            List<DateValueDTO> dateValueDTOS = utilizationService.calculateActualUtilizationPerMonthByConsultant(user.getUuid(), fromDate, toDate, budgetService.getBudgetHoursByPeriodAndSingleConsultant(user.getUuid(), fromDate, toDate));
            employeeUtilizationList.add(new KeyDateValueListDTO(user.getUuid(), dateValueDTOS));
        }

        return employeeUtilizationList;
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
    public List<DateValueDTO> getActualUtilizationPerMonthByTeam(@PathParam("teamuuid") String teamuuid, @QueryParam("fiscalYear") int fiscalYear) {
        LocalDate currentFiscalStartDate = LocalDate.of(fiscalYear, 7, 1);
        List<DateValueDTO> utilizationPerMonth = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            LocalDate localDate = currentFiscalStartDate.plusMonths(i);
            String[] uuids = findUseruuidsPerTeam(teamuuid, localDate);

            double billableHours = ((Number) em.createNativeQuery(
                    "SELECT SUM(wf.workduration) " +
                    "FROM work_full wf " +
                    "WHERE wf.registered >= :startDate " +
                    "  AND wf.registered < :endDate " +
                    "  AND wf.useruuid IN :uuids " +
                    "  AND wf.rate > 0 " +
                    "GROUP BY MONTH(registered), YEAR(registered)")
                    .setParameter("startDate", localDate)
                    .setParameter("endDate", localDate.plusMonths(1))
                    .setParameter("uuids", List.of(uuids))
                    .getResultList().stream().filter(Objects::nonNull).findAny().orElse(0.0)).doubleValue();
            log.debugf("billableHours = %f", billableHours);

            double availableHours = availabilityService.getSumOfAvailableHoursByUsersAndMonth(localDate, uuids);
            log.debugf("availableHours = %f", availableHours);

            utilizationPerMonth.add(new DateValueDTO(localDate, (billableHours / availableHours)));
        }

        return utilizationPerMonth;
    }

    @GET
    @Path("/actual/employees")
    public List<KeyDateValueListDTO> getEmployeeActualUtilizationPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate fromDate = dateIt(fromdate);
        LocalDate toDate = dateIt(todate);

        // List to store results
        List<KeyDateValueListDTO> employeeUtilizationList = new ArrayList<>();

        // Get all employees (this might come from the UserService or a different service)
        List<User> users = userService.findCurrentlyEmployedUsers(false, ConsultantType.CONSULTANT).stream().filter(u -> u.getUserStatus(toDate).getCompany().getUuid().equals(companyuuid)).toList();
        log.infof("Processing actual utilization for %d users", users.size());

        for (User user : users) {
            log.debugf("Calculating utilization for user %s", user.getUsername());
            List<DateValueDTO> dateValueDTOS = utilizationService.calculateActualUtilizationPerMonthByConsultant(user.getUuid(), fromDate, toDate, workService.findWorkHoursByUserAndPeriod(user.getUuid(), fromDate, toDate));
            employeeUtilizationList.add(new KeyDateValueListDTO(user.getFullname(), dateValueDTOS));
        }

        return employeeUtilizationList;
    }


    @SuppressWarnings("unchecked")
    private String[] findUseruuidsPerTeam(String teamuuid, LocalDate date) {
        return ((List<Tuple>) em.createNativeQuery(
                "SELECT t.useruuid AS useruuid " +
                "FROM teamroles AS t " +
                "WHERE t.teamuuid = :teamuuid " +
                "  AND t.membertype = 'MEMBER' " +
                "  AND t.startdate <= :date " +
                "  AND (t.enddate IS NULL OR t.enddate > :date)", Tuple.class)
                .setParameter("teamuuid", teamuuid)
                .setParameter("date", date)
                .getResultList()).stream()
                .map(tuple -> (String) tuple.get("useruuid"))
                .toArray(String[]::new);
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
