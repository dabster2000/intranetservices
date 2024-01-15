package dk.trustworks.intranet.aggregateservices.v2;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class UtilizationService {
    
    @Inject
    EntityManager em;
    
    
    public void calcUtilization(String useruuid, int year, int month) {
        String sql = "SELECT " +
                "    ed.useruuid, ed.year, ed.month, " +
                "    (100 * (wd.workduration / (ed.gross_available_hours - ed.paid_leave_hours - ed.non_payd_leave_hours - ed.non_payd_leave_hours - ed.maternity_leave_hours - ed.sick_hours - ed.vacation_hours - ed.unavailable_hours))) as actual_utilization, " +
                "    (100 * (bd.budgetHours / (ed.gross_available_hours - ed.paid_leave_hours - ed.non_payd_leave_hours - ed.non_payd_leave_hours - ed.maternity_leave_hours - ed.sick_hours - ed.vacation_hours - ed.unavailable_hours))) as contract_utilization " +
                "FROM employee_data_per_month ed " +
                "LEFT JOIN " +
                "    (select wdpm.useruuid, wdpm.year, wdpm.month, sum(wdpm.workduration) workduration from work_data_per_month wdpm where useruuid = '67874df9-7629-4dee-8ab5-4547e63b310e' and year = 2023 and month = 11 group by year, month, useruuid) wd on ed.month = wd.month and ed.year = wd.year and ed.useruuid = wd.useruuid " +
                "LEFT JOIN " +
                "    (select bdpm.useruuid, bdpm.year, bdpm.month, sum(bdpm.budgetHours) budgetHours from budget_data_per_month bdpm where useruuid = '67874df9-7629-4dee-8ab5-4547e63b310e' and year = 2023 and month = 11 group by year, month, useruuid) bd on ed.month = bd.month and ed.year = bd.year and ed.useruuid = bd.useruuid " +
                "where ed.useruuid = '67874df9-7629-4dee-8ab5-4547e63b310e' and ed.year = 2023 and ed.month = 11;";

    }
    
}
