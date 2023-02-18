package dk.trustworks.intranet.marginservice.services;

import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.financeservice.model.Finance;
import dk.trustworks.intranet.financeservice.model.enums.ExcelFinanceType;
import dk.trustworks.intranet.financeservice.services.FinanceService;
import dk.trustworks.intranet.marginservice.dto.ClientMarginResult;
import dk.trustworks.intranet.marginservice.dto.MarginResult;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.userservice.services.SalaryService;
import dk.trustworks.intranet.userservice.services.UserService;
import dk.trustworks.intranet.utils.DateUtils;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.getCurrentFiscalStartDate;
import static dk.trustworks.intranet.utils.DateUtils.getFiscalStartDateBasedOnDate;

@ApplicationScoped
public class MarginService {

    @Inject
    UserService userService;

    @Inject
    SalaryService salaryService;

    @Inject
    FinanceService financeService;

    @Inject
    ContractService contractService;

    @Inject
    WorkService workService;
    
    @GET
    @Path("/{useruuid}/{rate}")
    public MarginResult calculateMargin(@PathParam("useruuid") String useruuid, @PathParam("rate") int rate) {
        return calculateMarginBasedOnFiscalYear(useruuid, rate, LocalDate.now());
    }

    @GET
    @Path("/clients")
    public List<ClientMarginResult> calculateClientMargins(@QueryParam("fiscalyear") int fiscalYear) {
        LocalDate testDate = getCurrentFiscalStartDate().withYear(fiscalYear);
        LocalDate endDate = (getCurrentFiscalStartDate().getYear()==fiscalYear)?LocalDate.now().withDayOfMonth(1):getCurrentFiscalStartDate().withYear(fiscalYear).plusYears(1);
        List<ClientMarginResult> tempClientMarginResultList = new ArrayList<>();

        workService.findByPeriod(testDate, testDate.plusMonths(1).minusDays(1)).stream().filter(w -> w.getContractuuid()!=null && !w.getContractuuid().equals("")).forEach(work -> {
            //System.out.println("contractConsultant.getUseruuid() = " + contractConsultant.getUseruuid());
            double rate = work.getRate();
            if(rate>0) {
                MarginResult marginResult = calculateMarginBasedOnFiscalYear(work.getUseruuid(), (int) rate, work.getRegistered());
                //System.out.println("marginResult = " + marginResult.getMargin());
                /*
                double registeredHours = workList.stream().filter(work ->
                                work.getRegistered().getMonthValue() == finalTestDate.getMonthValue() &&
                                        work.getUseruuid().equals(contractConsultant.getUseruuid()))
                        .mapToDouble(WorkFull::getWorkduration).sum();

                 */
                //System.out.println("registeredHours = " + registeredHours);
                //if (registeredHours == 0.0) continue;
                tempClientMarginResultList.add(new ClientMarginResult(work.getClientuuid(), work.getRegistered(), marginResult, work.getWorkduration()));
            }
        });

        /*
        do {
            //System.out.println("testDate = " + stringIt(testDate));
            for (Contract contract : contractService.findByPeriod(testDate, testDate.plusMonths(1).minusDays(1))) {
                List<WorkFull> workList = workService.findWorkOnContract(contract.getUuid());
                if(workList.size()==0) continue;
                for (ContractConsultant contractConsultant : contract.getContractConsultants()) {
                    //System.out.println("contractConsultant.getUseruuid() = " + contractConsultant.getUseruuid());
                    double rate = contractConsultant.getRate();
                    //System.out.println("rate = " + rate);
                    if(rate==0.0) continue;
                    MarginResult marginResult = calculateMarginBasedOnFiscalYear(contractConsultant.getUseruuid(), (int) rate, testDate);
                    //System.out.println("marginResult = " + marginResult.getMargin());
                    LocalDate finalTestDate = testDate;
                    double registeredHours = workList.stream().filter(work ->
                            work.getRegistered().getMonthValue() == finalTestDate.getMonthValue() &&
                                    work.getUseruuid().equals(contractConsultant.getUseruuid()))
                            .mapToDouble(WorkFull::getWorkduration).sum();
                    //System.out.println("registeredHours = " + registeredHours);
                    if(registeredHours==0.0) continue;
                    tempClientMarginResultList.add(new ClientMarginResult(contract.getClientuuid(), testDate, marginResult, registeredHours));
                }
            }

            testDate = testDate.plusMonths(1);
        } while (testDate.isBefore(endDate));

         */

        List<ClientMarginResult> clientMarginResultList = new ArrayList<>();

        for (String clientuuid : tempClientMarginResultList.stream().map(ClientMarginResult::getClientuuid).collect(Collectors.toSet())) {
            //System.out.println("clientuuid = " + clientuuid);
            testDate = getCurrentFiscalStartDate().withYear(fiscalYear);
            //System.out.println("testDate = " + stringIt(testDate));
            do {
                LocalDate finalTestDate = testDate;
                //System.out.println("finalTestDate = " + stringIt(finalTestDate));
                double totalHoursRegisteredOnClientOnMonth = tempClientMarginResultList.stream().filter(clientMarginResult ->
                        clientMarginResult.getMonth().isEqual(finalTestDate) &&
                                clientMarginResult.getClientuuid().equals(clientuuid))
                        .mapToDouble(ClientMarginResult::getRegisteredHours).sum();
                //System.out.println("totalHoursRegisteredOnClientOnMonth = " + totalHoursRegisteredOnClientOnMonth);
                double marginTimesHoursRegisteredSum = tempClientMarginResultList.stream().filter(clientMarginResult ->
                        clientMarginResult.getMonth().isEqual(finalTestDate) &&
                                clientMarginResult.getClientuuid().equals(clientuuid)).mapToDouble(value ->
                        value.getRegisteredHours() * value.getMarginResult().getMargin()).sum();
                //System.out.println("marginTimesHoursRegisteredSum = " + marginTimesHoursRegisteredSum);
                double weightedMargin = marginTimesHoursRegisteredSum / totalHoursRegisteredOnClientOnMonth;
                //System.out.println("weightedMargin = " + weightedMargin);
                clientMarginResultList.add(new ClientMarginResult(clientuuid, testDate, new MarginResult((int) weightedMargin), 0.0));

                testDate = testDate.plusMonths(1);
            } while (testDate.isBefore(endDate));
        }

        return clientMarginResultList;
    }

    private MarginResult calculateMarginBasedOnFiscalYear(String useruuid, int rate, LocalDate fiscalYearDate) {
        int fiscalYear = getFiscalStartDateBasedOnDate(fiscalYearDate).getYear();
        LocalDate endDate = (getCurrentFiscalStartDate().getYear()==fiscalYear)?LocalDate.now():getCurrentFiscalStartDate().withYear(fiscalYear).plusYears(1);

        double hoursInFiscalYear = getHoursInFiscalYear(fiscalYear);

        double count = 0;
        double hourlyCostsSum = 0.0;
        /*
        double totalMonthExpenses = 0.0;
        double salarySum = 0.0;
        double userSalarySum = 0.0;
        */

        LocalDate date = getCurrentFiscalStartDate().withYear(fiscalYear);
        do {
            User user = userService.findUserByUuid(useruuid, false);
            if(!user.getUserStatus(date).getStatus().equals(StatusType.ACTIVE)) {
                date = date.plusMonths(1);
                continue;
            }

            /*
            2021-07-01
            consultantCount = 24
            totalMonthExpenses = 2325tkr
            totalConsultantSalaries = 1695tkr
            totalOtherExpenses = totalMonthExpenses - totalConsultantSalaries = 2325 - 1461 = 864
            consultantOtherExpenses = totalOtherExpenses / 24 = 36tkr
            consultantSalary = 62tkr
            consultantCosts = consultantOtherExpenses + consultantSalary = 36 + 62 = 98tkr
            hoursPerMonth = 120
            hourlyCostsSum = consultantCosts / hoursPerMonth = 88.000 / 120 = 819kr
            rate = 1150
            margin = rate - hourlyCostsSum = 1150 - 819 = 331kr


            date = 2021-07-01
            marginservice           | consultantSalary = 62000
            marginservice           | consultantCount = 24.0
            marginservice           | totalConsultantSalaries = 1461000.0
            marginservice           | totalMonthExpenses = 2324988.01
            marginservice           | totalOtherExpenses = 863988.0099999998
            marginservice           | consultantOtherExpenses = 35999.500416666655
            marginservice           | consultantCosts = 97999.50041666665
            marginservice           | hoursPerMonth = 119.60000000000069
            marginservice           | hourlyCostsSum = 819.3938161928602

            marginservice           | date = 2021-10-01
            marginservice           | consultantSalary = 62000
            marginservice           | consultantCount = 29.0
            marginservice           | totalConsultantSalaries = 1665000.0
            marginservice           | totalMonthExpenses = 2670880.74
            marginservice           | totalOtherExpenses = 1005880.7400000002
            marginservice           | consultantOtherExpenses = 34685.5427586207
            marginservice           | consultantCosts = 96685.5427586207
            marginservice           | hoursPerMonth = 119.60000000000069
            marginservice           | hourlyCostsSum = 808.4075481489978


            marginservice           | date = 2021-11-01
            marginservice           | consultantSalary = 62000

            marginservice           | count = 4.0
            marginservice           | hourlyCostsSum = 808.4075481489978
            marginservice           | margin = 947.8981129627506
             */

            int consultantSalary = user.getSalary(date).getSalary();//userService.getUserSalary(useruuid, stringIt(date)).getSalary();
            //userSalarySum += consultantSalary;

            List<Finance> expenses = financeService.findByMonth(date);
            Optional<Finance> totalConsultantCosts = expenses.stream().filter(companyExpense -> companyExpense.getExpensetype().equals(ExcelFinanceType.LØNNINGER)).findFirst();

            if(totalConsultantCosts.isEmpty()) break;
            if(totalConsultantCosts.get().getAmount() < 1000000) break;

            double consultantCount = 0;
            double totalConsultantSalaries = 0;
            for (User consultant : userService.findUsersByDateAndStatusListAndTypes(date, "ACTIVE", "CONSULTANT", true)) {
                totalConsultantSalaries += salaryService.getUserSalaryByMonth(consultant.getUuid(), date).getSalary();
                consultantCount++;
            }
            double totalMonthExpenses = expenses.stream().mapToDouble(Finance::getAmount).sum();

            double totalOtherExpenses = totalMonthExpenses - totalConsultantSalaries;
            double consultantOtherExpenses = totalOtherExpenses / consultantCount;
            double consultantCosts = consultantOtherExpenses + consultantSalary;
            double hoursPerMonth = hoursInFiscalYear / 12;

            hourlyCostsSum += (consultantCosts / hoursPerMonth);

            count++;
            date = date.plusMonths(1);
        } while (date.isBefore(endDate));

        double margin = rate - (hourlyCostsSum / count);

        return new MarginResult((int) Math.floor(margin));
    }

    private double getHoursInFiscalYear(int fiscalYear) {
        LocalDate countFullDaysDate = getCurrentFiscalStartDate().withYear(fiscalYear);
        double hoursInFiscalYear = 0;
        int countFridays = DateUtils.countWeekdayOccurances(DayOfWeek.FRIDAY, countFullDaysDate, countFullDaysDate.plusYears(1));
        do {
            if(DateUtils.isWorkday(countFullDaysDate)) hoursInFiscalYear += 7.4;
            countFullDaysDate = countFullDaysDate.plusDays(1);
        } while (countFullDaysDate.isBefore(getCurrentFiscalStartDate().withYear(fiscalYear).plusYears(1)));
        hoursInFiscalYear -= countFridays * 2;
        hoursInFiscalYear = hoursInFiscalYear - (7.4 * 5 * 6) - (7.4 * 5) - (7.4 * 5) - (7.4 * 10) - 200; // [vacation] - [1 week knowledge budget] - [1 week sickness] - [1 week other]
        return hoursInFiscalYear;
    }
}