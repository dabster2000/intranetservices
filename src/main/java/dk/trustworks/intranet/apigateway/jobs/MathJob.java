package dk.trustworks.intranet.apigateway.jobs;

import dk.trustworks.intranet.aggregateservices.CompanyDataService;
import dk.trustworks.intranet.aggregateservices.FinanceService;
import dk.trustworks.intranet.aggregateservices.RevenueService;
import dk.trustworks.intranet.aggregateservices.model.CompanyAggregateData;
import dk.trustworks.intranet.dto.FinanceDocument;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.services.SalaryService;
import dk.trustworks.intranet.userservice.services.TeamService;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.getCurrentFiscalStartDate;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;

@JBossLog
@ApplicationScoped
public class MathJob {

    @Inject
    TeamService teamService;

    @Inject
    FinanceService financeService;

    @Inject
    SalaryService salaryService;

    @Inject
    CompanyDataService companyDataService;


    @Inject
    RevenueService revenueService;


    //@Scheduled(every="5m")
    void math() {
        log.debug("MathJob.math");
        LocalDate startDate = getCurrentFiscalStartDate();

        log.info(stringIt(startDate));

        //double avgTeammembersByFiscalyear = teamService.avgTeammembersByFiscalyear(2021);
        //log.info("avgTeammembersByFiscalyear = " + avgTeammembersByFiscalyear);

        List<Integer> totalSalariesList = new ArrayList<>();
        double totalExpenses = 0.0;
        double totalRevenueTest = 0.0;
        double totalRevenue = 0.0;
        for (int i = 0; i < 6; i++) {
            LocalDate testDate = startDate.plusMonths(i);
            List<User> teammembers = teamService.getTeammembersByTeamleadBonusEnabledByMonth(testDate);

            totalExpenses += financeService.getAllExpensesByMonth(testDate).stream().mapToDouble(FinanceDocument::sum).sum();

            totalRevenueTest += companyDataService.getDataMap(testDate, testDate.plusMonths(1)).stream().filter(c -> c.getMonth().isEqual(testDate)).mapToDouble(CompanyAggregateData::getRegisteredAmount).sum();
            totalRevenue += revenueService.getRegisteredRevenueForSingleMonth(testDate);

            for (User teammember : teammembers) {
                totalSalariesList.add(salaryService.getUserSalaryByMonth(teammember.getUuid(), testDate).getSalary());
            }
            log.info(stringIt(startDate.plusMonths(i)));
            log.info("Revenue: "+totalRevenueTest);
            log.info("Revenue: "+totalRevenue);
            log.info("Expenses: "+totalExpenses);
            log.info("Avg salaries: "+totalSalariesList.stream().mapToInt(value -> value).average().orElse(0.0));
            log.info("Sum salaries: "+totalSalariesList.stream().mapToInt(value -> value).sum());
        }
        log.info("totalExpenses = " + totalExpenses);



    }
}
