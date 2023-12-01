package dk.trustworks.intranet.aggregates.utilization.resources;

import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.aggregateservices.model.EmployeeDataPerMonth;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.Tuple;
import javax.ws.rs.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "utilization")
@JBossLog
@Path("/utilization")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class UtilizationResource {

    @Inject
    EntityManager em;

    private final String sql = "SELECT " +
            "    ed.useruuid, ed.year, ed.month, " +
            "    (100 * (wd.workduration / (ed.gross_available_hours - ed.paid_leave_hours - ed.non_payd_leave_hours - ed.non_payd_leave_hours - ed.maternity_leave_hours - ed.sick_hours - ed.vacation_hours - ed.unavailable_hours))) as actual_utilization, " +
            "    (100 * (bd.budgetHours / (ed.gross_available_hours - ed.paid_leave_hours - ed.non_payd_leave_hours - ed.non_payd_leave_hours - ed.maternity_leave_hours - ed.sick_hours - ed.vacation_hours - ed.unavailable_hours))) as contract_utilization " +
            "FROM employee_data_per_month ed " +
            "LEFT JOIN " +
            "    (select wdpm.useruuid, wdpm.year, wdpm.month, sum(wdpm.workduration) workduration from work_data_per_month wdpm where useruuid = '67874df9-7629-4dee-8ab5-4547e63b310e' and year = 2023 and month = 11 group by year, month, useruuid) wd on ed.month = wd.month and ed.year = wd.year and ed.useruuid = wd.useruuid " +
            "LEFT JOIN " +
            "    (select bdpm.useruuid, bdpm.year, bdpm.month, sum(bdpm.budgetHours) budgetHours from budget_data_per_month bdpm where useruuid = '67874df9-7629-4dee-8ab5-4547e63b310e' and year = 2023 and month = 11 group by year, month, useruuid) bd on ed.month = bd.month and ed.year = bd.year and ed.useruuid = bd.useruuid " +
            "where ed.useruuid = '67874df9-7629-4dee-8ab5-4547e63b310e' and ed.year = 2023 and ed.month = 11;";

    @GET
    @Path("/budget")
    public List<DateValueDTO> getBudgetUtilizationPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        List<DateValueDTO> availabilityPerMonth = ((List<Tuple>) em.createNativeQuery("select " +
                "    cast(concat(e.year,'-',e.month,'-01') as date) as date, (sum(e.gross_available_hours - e.paid_leave_hours - e.non_payd_leave_hours - e.maternity_leave_hours - e.sick_hours - e.vacation_hours - e.unavailable_hours)) as value " +
                "from " +
                "    availability_document e " +
                "     WHERE consultant_type = 'CONSULTANT' " +
                "     AND status_type != 'TERMINATED' " +
                "group by e.year, e.month;", Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        ((Date) tuple.get("date")).toLocalDate(),
                        ((BigDecimal) tuple.get("value")).doubleValue()
                ))
                .toList();
        List<DateValueDTO> budgetsPerMonth = ((List<Tuple>) em.createNativeQuery("select " +
                "    b.year as year, b.month as month, (sum(b.budgetHours)) as value " +
                "from " +
                "    budget_data_per_month b " +
                "group by " +
                "    b.year, b.month;", Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(LocalDate.of((int) tuple.get("year"), (int) tuple.get("month"), 1),
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
    @Path("/budget/teams/{teamuuid}")
    public List<DateValueDTO> getBudgetUtilizationPerMonthByTeam(@PathParam("teamuuid") String teamuuid) {
        LocalDate currentFiscalStartDate = DateUtils.getCurrentFiscalStartDate();
        List<DateValueDTO> availabilityPerMonth = new ArrayList<>();
        List<DateValueDTO> budgetsPerMonth = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LocalDate localDate = currentFiscalStartDate.plusMonths(i);

            String[] useruuidsPerTeam = findUseruuidsPerTeam(teamuuid, localDate);

            availabilityPerMonth.addAll(((List<Tuple>) em.createNativeQuery("select " +
                    "    cast(concat(e.year,'-',e.month,'-01') as date) as date, " +
                    "    GREATEST(0, ROUND(sum(e.gross_available_hours - e.paid_leave_hours - e.non_payd_leave_hours - e.maternity_leave_hours - e.sick_hours - e.vacation_hours - e.unavailable_hours))) value " +
                    "from " +
                    "    availability_document e " +
                    "WHERE consultant_type = 'CONSULTANT' " +
                    "     AND status_type != 'TERMINATED' " +
                    "     AND useruuid in ('"+String.join("','", useruuidsPerTeam)+"') " +
                    "     AND e.year = "+localDate.getYear()+" " +
                    "     AND e.month = "+localDate.getMonthValue()+" " +
                    "group by e.year, e.month", Tuple.class).getResultList()).stream()
                    .map(tuple -> new DateValueDTO(
                            ((Date) tuple.get("date")).toLocalDate(),
                            ((BigDecimal) tuple.get("value")).doubleValue()
                    ))
                    .toList());
            budgetsPerMonth.addAll(((List<Tuple>) em.createNativeQuery("select " +
                    "    b.month as date, (sum(b.budgetHours)) as value " +
                    "from " +
                    "    budget_document b " +
                    "where " +
                    "    b.useruuid in ('"+String.join("','", useruuidsPerTeam)+"') " +
                    "     AND b.month = '"+DateUtils.stringIt(localDate)+"' " +
                    "group by " +
                    "    b.month;", Tuple.class).getResultList()).stream()
                    .map(tuple -> new DateValueDTO(
                            ((Date) tuple.get("date")).toLocalDate(),
                            (Double) tuple.get("value")
                    ))
                    .toList());
        }

        return availabilityPerMonth
                .stream()
                .peek(availability -> budgetsPerMonth.stream()
                        .filter(budget -> budget.getDate().equals(availability.getDate()))
                        .findFirst()
                        .ifPresentOrElse(bud -> availability.setValue(bud.getValue() / availability.getValue()), () -> availability.setValue(0.0)))
                .toList();
    }

    @GET
    @Path("/budget/users/{useruuid}")
    public List<DateValueDTO> getBudgetUtilizationPerMonthByConsultant(@PathParam("useruuid") String useruuid) {
        List<DateValueDTO> availabilityPerMonth = ((List<Tuple>) em.createNativeQuery("select " +
                "    cast(concat(e.year,'-',e.month,'-01') as date) as date, " +
                "    (sum(e.gross_available_hours - e.paid_leave_hours - e.non_payd_leave_hours - e.maternity_leave_hours - e.sick_hours - e.vacation_hours - e.unavailable_hours)) as value " +
                "from " +
                "    availability_document e " +
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
                "    b.month as date, (sum(b.budgetHours)) as value " +
                "from " +
                "    budget_document b " +
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
    // TODO: MOVE TO NEW UTILIZATION CALCULATOR
    public List<DateValueDTO> getActualUtilizationPerMonth() {
        String sql = "select " +
                "    cast(concat(e.year,'-',e.month,'-01') as date) as date, " +
                "    sum(e.registered_billable_hours) / sum(e.gross_available_hours - e.paid_leave_hours - e.non_payd_leave_hours - e.maternity_leave_hours - e.sick_hours - e.vacation_hours - e.unavailable_hours) as value " +
                "from availability_document e " +
                "where e.status_type = 'ACTIVE' and e.consultant_type = 'CONSULTANT' " +
                "group by year, month;";
        return new ArrayList<>();
        /*
                ((List<Tuple>) em.createNativeQuery(sql, Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        ((Date) tuple.get("date")).toLocalDate(),
                        ((BigDecimal) tuple.get("value")).doubleValue()
                ))
                .toList();

         */
    }

    @GET
    @Path("/actual/teams/{teamuuid}")
    public List<DateValueDTO> getActualUtilizationPerMonthByTeam(@PathParam("teamuuid") String teamuuid) {;
        LocalDate currentFiscalStartDate = DateUtils.getCurrentFiscalStartDate();
        List<DateValueDTO> utilizationPerMonth = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LocalDate localDate = currentFiscalStartDate.plusMonths(i);
            utilizationPerMonth.addAll(((List<Tuple>) em.createNativeQuery("select " +
                    "    cast(concat(e.year,'-',e.month,'-01') as date) as date, " +
                    "    sum(e.registered_billable_hours) / sum(e.gross_available_hours - e.paid_leave_hours - e.non_payd_leave_hours - e.maternity_leave_hours - e.sick_hours - e.vacation_hours - e.unavailable_hours) as value " +
                    "from availability_document e " +
                    "where e.status_type = 'ACTIVE' and e.consultant_type = 'CONSULTANT' and e.useruuid in ('"+String.join("','", findUseruuidsPerTeam(teamuuid, localDate))+"') " +
                    "     AND e.year = "+localDate.getYear()+" " +
                    "     AND e.month = "+localDate.getMonthValue()+" " +
                    "group by year, month;", Tuple.class).getResultList()).stream()
                    .map(tuple -> new DateValueDTO(
                            ((Date) tuple.get("date")).toLocalDate(),
                            ((BigDecimal) tuple.get("value")).doubleValue()
                    ))
                    .toList());
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
        return EmployeeDataPerMonth
                .<EmployeeDataPerMonth>stream("consultantType = 'CONSULTANT' AND status != 'TERMINATED' AND useruuid = ?1", useruuid)
                .map(employeeDataPerMonth -> new DateValueDTO(
                        LocalDate.of(employeeDataPerMonth.getYear(), employeeDataPerMonth.getMonth(), 1),
                        employeeDataPerMonth.getActualUtilization()
                ))
                .toList();
    }

}
