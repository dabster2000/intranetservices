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
    @SuppressWarnings("unchecked")
    public List<DateValueDTO> getBudgetUtilizationPerMonthByTeam(
            @PathParam("teamuuid") String teamuuid,
            @QueryParam("fromdate") String fromdate,
            @QueryParam("todate") String todate) {
        LocalDate fromDate = dateIt(fromdate);
        LocalDate toDate = dateIt(todate);

        String sql = """
                SELECT
                    bdd.year,
                    bdd.month,
                    COALESCE(SUM(bbpd.budgetHours), 0) AS budget_hours,
                    GREATEST(0.0, SUM(bdd.gross_available_hours
                        - COALESCE(bdd.unavailable_hours, 0)
                        - COALESCE(bdd.vacation_hours, 0)
                        - COALESCE(bdd.sick_hours, 0)
                        - COALESCE(bdd.maternity_leave_hours, 0)
                        - COALESCE(bdd.non_payd_leave_hours, 0)
                        - COALESCE(bdd.paid_leave_hours, 0))) AS available_hours
                FROM teamroles tr
                JOIN bi_data_per_day bdd
                    ON bdd.useruuid = tr.useruuid
                    AND bdd.document_date >= :fromDate
                    AND bdd.document_date < :toDate
                    AND bdd.status_type = 'ACTIVE'
                    AND bdd.consultant_type = 'CONSULTANT'
                LEFT JOIN bi_budget_per_day bbpd
                    ON bbpd.useruuid = tr.useruuid
                    AND bbpd.year = bdd.year
                    AND bbpd.month = bdd.month
                    AND bbpd.document_date = bdd.document_date
                WHERE tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= bdd.document_date
                    AND (tr.enddate IS NULL OR tr.enddate > bdd.document_date)
                GROUP BY bdd.year, bdd.month
                ORDER BY bdd.year, bdd.month
                """;

        List<Tuple> results = em.createNativeQuery(sql, Tuple.class)
                .setParameter("teamId", teamuuid)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        return results.stream()
                .map(t -> {
                    double budget = ((Number) t.get("budget_hours")).doubleValue();
                    double available = ((Number) t.get("available_hours")).doubleValue();
                    return new DateValueDTO(
                            LocalDate.of(((Number) t.get("year")).intValue(), ((Number) t.get("month")).intValue(), 1),
                            available > 0 ? budget / available : 0.0
                    );
                })
                .toList();
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
    @SuppressWarnings("unchecked")
    public List<DateValueDTO> getActualUtilizationPerMonthByTeam(
            @PathParam("teamuuid") String teamuuid,
            @QueryParam("fromdate") String fromdate,
            @QueryParam("todate") String todate) {
        LocalDate fromDate = dateIt(fromdate);
        LocalDate toDate = dateIt(todate);

        String sql = """
                SELECT
                    bdd.year,
                    bdd.month,
                    COALESCE(SUM(CASE WHEN wf.rate > 0 THEN wf.workduration ELSE 0 END), 0) AS billable_hours,
                    GREATEST(0.0, SUM(DISTINCT_BDD.net_available)) AS available_hours
                FROM teamroles tr
                JOIN (
                    SELECT
                        useruuid, year, month, document_date,
                        GREATEST(0.0, gross_available_hours
                            - COALESCE(unavailable_hours, 0)
                            - COALESCE(vacation_hours, 0)
                            - COALESCE(sick_hours, 0)
                            - COALESCE(maternity_leave_hours, 0)
                            - COALESCE(non_payd_leave_hours, 0)
                            - COALESCE(paid_leave_hours, 0)) AS net_available
                    FROM bi_data_per_day
                    WHERE document_date >= :fromDate
                        AND document_date < :toDate
                        AND status_type = 'ACTIVE'
                        AND consultant_type = 'CONSULTANT'
                ) DISTINCT_BDD
                    ON DISTINCT_BDD.useruuid = tr.useruuid
                LEFT JOIN work_full wf
                    ON wf.useruuid = tr.useruuid
                    AND wf.registered = DISTINCT_BDD.document_date
                WHERE tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= DISTINCT_BDD.document_date
                    AND (tr.enddate IS NULL OR tr.enddate > DISTINCT_BDD.document_date)
                GROUP BY DISTINCT_BDD.year, DISTINCT_BDD.month
                ORDER BY DISTINCT_BDD.year, DISTINCT_BDD.month
                """;

        List<Tuple> results = em.createNativeQuery(sql, Tuple.class)
                .setParameter("teamId", teamuuid)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        return results.stream()
                .map(t -> {
                    double billable = ((Number) t.get("billable_hours")).doubleValue();
                    double available = ((Number) t.get("available_hours")).doubleValue();
                    return new DateValueDTO(
                            LocalDate.of(((Number) t.get("year")).intValue(), ((Number) t.get("month")).intValue(), 1),
                            available > 0 ? billable / available : 0.0
                    );
                })
                .toList();
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
