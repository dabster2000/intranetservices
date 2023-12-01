package dk.trustworks.intranet.aggregateservices.v2;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.aggregateservices.BudgetService;
import dk.trustworks.intranet.aggregateservices.model.BudgetDocumentPerDay;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.aggregateservices.model.BudgetDocumentPerMonth;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.aggregateservices.model.EmployeeDataPerDay;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.getWeekdaysInPeriod;

@JBossLog
@ApplicationScoped
public class BudgetCalculatingExecutor {

    @Inject
    UserService userService;

    @Inject
    ClientService clientService;

    @Inject
    ContractService contractService;

    @Inject
    BudgetService budgetService;

    public List<BudgetDocumentPerDay> findAllBudgetData() {
        return BudgetDocumentPerMonth.<BudgetDocumentPerMonth>listAll().stream().map(bdm -> new BudgetDocumentPerDay(LocalDate.of(bdm.getYear(), bdm.getMonth(), 1), bdm.getClient(), bdm.getUser(), bdm.getContract(), bdm.getBudgetHours(), bdm.getBudgetHours(), bdm.getRate())).toList();
    }

    public List<BudgetDocumentPerDay> findAllBudgetDataByUserAndPeriod(String useruuid, LocalDate startDate, LocalDate endDate) {
        return BudgetDocumentPerMonth.<BudgetDocumentPerMonth>list("STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') " +
                "      BETWEEN STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') " +
                "      AND STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and useruuid = ?5", startDate.getYear(), startDate.getMonthValue(), endDate.getYear(), endDate.getMonthValue(), useruuid)
                .stream().map(bdm -> new BudgetDocumentPerDay(LocalDate.of(bdm.getYear(), bdm.getMonth(), 1), bdm.getClient(), bdm.getUser(), bdm.getContract(), bdm.getBudgetHours(), bdm.getBudgetHours(), bdm.getRate())).toList();
        //return BudgetDocumentPerMonth.find("useruuid like ?1 and month >= ?2 and month < ?3", useruuid, startDate, endDate).list();
    }

    public void calcBudgetsV2(DateRangeMap dateRangeMap) {
        LocalDate startDate = dateRangeMap.getFromDate();
        LocalDate endDate = dateRangeMap.getEndDate();
        log.info("BudgetServiceCache.calcBudgets, lookupMonth = " + startDate);
        QuarkusTransaction.requiringNew().run(() -> {
            BudgetDocumentPerDay.delete("documentDate >= ?1 and documentDate < ?2", startDate, endDate);
        });

        List<Client> clientList = clientService.listAllClients();
        List<User> userList = userService.listAll(true);
        List<Contract> contracts = contractService.findByPeriod(startDate, endDate);

        LocalDate lookupDate = startDate;
        do {
            if(!DateUtils.isWorkday(lookupDate)) {
                lookupDate = lookupDate.plusDays(1);
                continue;
            }
            for (User user : userList) {
                List<BudgetDocumentPerDay> budgetDocumentPerDayList = new ArrayList<>();
                List<Contract> activeContracts = contractService.getContractsByDate(contracts, user, lookupDate);
                for (Contract contract : activeContracts) {
                    ContractConsultant userContract = contract.findByUserAndDate(user, lookupDate);
                    if(userContract == null) continue;
                    if(userContract.getHours()==0.0) continue;

                    BudgetDocumentPerDay budgetDocumentPerDay = new BudgetDocumentPerDay(lookupDate, getClient(clientList, contract), user, contract, userContract.getHours() / 5.0, userContract.getHours() / 5.0, userContract.getRate());

                    if(budgetDocumentPerDay.getBudgetHours()==0.0) continue;
                    budgetDocumentPerDayList.add(budgetDocumentPerDay);
                }
                List<BudgetDocumentPerDay> budgetDocumentPerDays = adjustForAvailability(budgetDocumentPerDayList, lookupDate);
                QuarkusTransaction.requiringNew().run(() -> BudgetDocumentPerDay.persist(budgetDocumentPerDays));
            }
            lookupDate = lookupDate.plusDays(1);
        } while (lookupDate.isBefore(endDate));
    }

    /*
    public void calcBudgets(DateRangeMap dateRangeMap) {
        LocalDate startDate = dateRangeMap.getFromDate();
        log.info("BudgetServiceCache.calcBudgets, lookupMonth = " + startDate);
        QuarkusTransaction.requiringNew().run(() -> {
            BudgetDocument.delete("month >= ?1 and month < ?2", startDate, startDate.plusMonths(1));
        });
        List<Client> clientList = clientService.listAllClients();
        List<User> userList = userService.listAll(true);
        List<Budget> budgets = budgetService.findByMonthAndYear(startDate);
        List<Contract> contracts = contractService.findByPeriod(startDate, startDate.plusMonths(1));
        List<BudgetDocument> budgetDocumentList = new ArrayList<>();
        for (User user : userList) {
            List<Contract> activeContracts = contractService.getContractsByDate(contracts, user, startDate);
            for (Contract contract : activeContracts) {
                if(contract.getContractType().equals(ContractType.PERIOD)) {
                    ContractConsultant userContract = contract.findByUser(user);
                    if(userContract == null) continue;

                    BudgetDocument budgetDocument = createBudgetDocument(user, startDate, contract, userContract, clientList);
                    if (budgetDocument == null) {
                        continue;
                    }
                    if(budgetDocument.getBudgetHours()==0) continue;
                    budgetDocumentList.add(budgetDocument);
                } else {
                    ContractConsultant userContract = contract.findByUser(user);
                    if(userContract == null || userContract.getRate() == 0.0) continue;
                    double budget = budgets.stream()
                            .filter(budgetNew ->
                                    budgetNew.getConsultantuuid().equals(userContract.getUuid()) &&
                                            budgetNew.getYear() == startDate.getYear() &&
                                            (budgetNew.getMonth()+1) == startDate.getMonthValue())
                            .mapToDouble(budgetNew -> budgetNew.getBudget() / userContract.getRate()).sum();

                    if(budget==0) continue;
                    BudgetDocument budgetDocument = new BudgetDocument(startDate, getClient(clientList, contract), user, contract, budget, budget, contract.findByUser(user).getRate());
                    budgetDocumentList.add(budgetDocument);
                }
            }
        }
        List<BudgetDocument> budgetDocuments = adjustForAvailability(budgetDocumentList, startDate);
        QuarkusTransaction.requiringNew().run(() -> BudgetDocument.persist(budgetDocuments));
    }

     */

    private List<BudgetDocumentPerDay> adjustForAvailability(List<BudgetDocumentPerDay> budgetDocumentPerDayList, LocalDate lookupMonth) {
        List<EmployeeDataPerDay> employeeDataPerDay = EmployeeDataPerDay.<EmployeeDataPerDay>stream("documentDate = ?1 AND consultantType = 'CONSULTANT'", lookupMonth).toList();
        List<String> userList = budgetDocumentPerDayList.stream().map(b -> b.getUser().getUuid()).distinct().toList();
        for (String useruuid : userList) {
            List<BudgetDocumentPerDay> budgetDocumentPerDays = budgetDocumentPerDayList
                    .stream()
                    .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(useruuid) && budgetDocument.getDocumentDate().isEqual(lookupMonth))
                    .toList();
            EmployeeDataPerDay employeeData = employeeDataPerDay
                    .stream()
                    .filter(a -> a.getUser().getUuid().equals(useruuid)).findFirst().orElse(new EmployeeDataPerDay());
                    //.mapToDouble(EmployeeDataPerDay::getNetAvailableHours).sum();


            double sum = budgetDocumentPerDays.stream().mapToDouble(BudgetDocumentPerDay::getBudgetHours).sum();

            for (BudgetDocumentPerDay budgetDocumentPerDay : budgetDocumentPerDays) {
                budgetDocumentPerDay.setCompany(employeeData.getCompany());
                if(sum > employeeData.getNetAvailableHours()) {
                    double factor = budgetDocumentPerDay.getBudgetHours() / sum;
                    budgetDocumentPerDay.setBudgetHours(factor * employeeData.getNetAvailableHours());
                }
            }
        }
        return budgetDocumentPerDayList;
    }

    private BudgetDocumentPerDay createBudgetDocumentV2(User user, LocalDate startDate, Contract contract, ContractConsultant userContract, List<Client> clientList) {
        BudgetDocumentPerDay result = null;
        double budget = userContract.getHours() / 5.0; // (f.eks. 35 timer)
        if (budget != 0.0) {
            //double monthBudget = budget * DateUtils.getWeekdaysInPeriod(startDate, startDate.plusMonths(1)); // f.eks. 2019-12-01, 18 days / 5 = 3,6 weeks * 35 (budget) = 126 hours
            result = new BudgetDocumentPerDay(startDate, getClient(clientList, contract), user, contract, budget, budget, userContract.getRate());
        }

        return result;
    }

    private BudgetDocumentPerDay createBudgetDocument(User user, LocalDate startDate, Contract contract, ContractConsultant userContract, List<Client> clientList) {
        BudgetDocumentPerDay result = null;
        double budget = userContract.getHours() / 5.0; // (f.eks. 35 timer)
        if (budget != 0.0) {
            double monthBudget = budget * getWeekdaysInPeriod(startDate, startDate.plusMonths(1)); // f.eks. 2019-12-01, 18 days / 5 = 3,6 weeks * 35 (budget) = 126 hours
            result = new BudgetDocumentPerDay(startDate, getClient(clientList, contract), user, contract, monthBudget, monthBudget, userContract.getRate());
        }

        return result;
    }

    private Client getClient(List<Client> clientList, Contract contract) {
        return clientList.stream().filter(client -> client.getUuid().equals(contract.getClientuuid())).findAny().orElseThrow(() -> new RuntimeException(contract.toString()));
    }

}
