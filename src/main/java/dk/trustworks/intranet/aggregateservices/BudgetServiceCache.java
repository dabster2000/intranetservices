package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.contracts.model.Budget;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dto.BudgetDocument;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.model.EmployeeDataPerDay;
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
import static dk.trustworks.intranet.utils.DateUtils.stringIt;

@JBossLog
@ApplicationScoped
public class BudgetServiceCache {

    @Inject
    UserService userService;

    @Inject
    ClientService clientService;

    @Inject
    ContractService contractService;

    @Inject
    BudgetService budgetService;

    public List<BudgetDocument> findAllBudgetData() {
        return BudgetDocument.listAll();
    }

    public List<BudgetDocument> findAllBudgetDataByUserAndPeriod(String useruuid, LocalDate startDate, LocalDate endDate) {
        return BudgetDocument.find("useruuid like ?1 and month >= ?2 and month < ?3", useruuid, startDate, endDate).list();
    }

    public void calcBudgetsV2(DateRangeMap dateRangeMap) {
        LocalDate startDate = dateRangeMap.getFromDate();
        LocalDate endDate = dateRangeMap.getEndDate();
        log.info("BudgetServiceCache.calcBudgets, lookupMonth = " + startDate);
        QuarkusTransaction.requiringNew().run(() -> {
            BudgetDocument.delete("month >= ?1 and month < ?2", startDate, endDate);
        });

        List<Client> clientList = clientService.listAllClients();
        List<User> userList = userService.listAll(true);
        List<Budget> budgets = budgetService.findByMonthAndYear(startDate);
        List<Contract> contracts = contractService.findByPeriod(startDate, endDate);

        LocalDate lookupDate = startDate;
        do {
            if(!DateUtils.isWorkday(lookupDate)) {
                lookupDate = lookupDate.plusDays(1);
                continue;
            }
            for (User user : userList) {
                List<BudgetDocument> budgetDocumentList = new ArrayList<>();
                List<Contract> activeContracts = contractService.getContractsByDate(contracts, user, lookupDate);
                for (Contract contract : activeContracts) {
                    if(contract.getContractType().equals(ContractType.PERIOD)) {
                        ContractConsultant userContract = contract.findByUser(user);
                        if(userContract == null) continue;
                        if(userContract.getHours()==0.0) continue;

                        BudgetDocument budgetDocument = new BudgetDocument(lookupDate, getClient(clientList, contract), user, contract, userContract.getHours() / 5.0, userContract.getHours() / 5.0, userContract.getRate());

                        if(budgetDocument.getBudgetHours()==0.0) continue;
                        budgetDocumentList.add(budgetDocument);
                    } else {
                        ContractConsultant userContract = contract.findByUser(user);
                        if(userContract == null || userContract.getRate() == 0.0) continue;

                        int weekdaysInPeriod = getWeekdaysInPeriod(
                                lookupDate.withDayOfMonth(1).isBefore(contract.getActiveFrom())?
                                        contract.getActiveFrom():lookupDate.withDayOfMonth(1),
                                lookupDate.withDayOfMonth(1).plusMonths(1).isAfter(contract.getActiveTo())?
                                        contract.getActiveTo():lookupDate.withDayOfMonth(1).plusMonths(1));
                        System.out.println(stringIt(lookupDate)+": weekdaysInPeriod = " + weekdaysInPeriod);
                        LocalDate finalLookupDate = lookupDate;
                        double budget = budgets.stream()
                                .filter(budgetNew ->
                                        budgetNew.getConsultantuuid().equals(userContract.getUuid()) &&
                                                budgetNew.getYear() == finalLookupDate.getYear() &&
                                                (budgetNew.getMonth()+1) == finalLookupDate.getMonthValue())
                                .mapToDouble(budgetNew -> budgetNew.getBudget() / userContract.getRate()).sum();

                        if(budget==0) continue;
                        BudgetDocument budgetDocument = new BudgetDocument(lookupDate, getClient(clientList, contract), user, contract, budget / weekdaysInPeriod, budget / weekdaysInPeriod, contract.findByUser(user).getRate());
                        budgetDocumentList.add(budgetDocument);
                    }
                }
                List<BudgetDocument> budgetDocuments = adjustForAvailability(budgetDocumentList, lookupDate);
                QuarkusTransaction.requiringNew().run(() -> BudgetDocument.persist(budgetDocuments));
            }
            lookupDate = lookupDate.plusDays(1);
        } while (lookupDate.isBefore(endDate));
    }

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

    private List<BudgetDocument> adjustForAvailability(List<BudgetDocument> budgetDocumentList, LocalDate lookupMonth) {
        List<EmployeeDataPerDay> employeeDataPerDay = EmployeeDataPerDay.<EmployeeDataPerDay>stream("documentDate = ?1 AND consultantType = 'CONSULTANT' AND statusType != 'TERMINATED'", lookupMonth).toList();
        List<String> userList = budgetDocumentList.stream().map(b -> b.getUser().getUuid()).distinct().toList();
        for (String useruuid : userList) {
            System.out.println("useruuid = " + useruuid);
            System.out.println("lookupMonth = " + lookupMonth);
            List<BudgetDocument> budgetDocuments = budgetDocumentList
                    .stream()
                    .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(useruuid) && budgetDocument.getMonth().isEqual(lookupMonth))
                    .toList();
            double availability = employeeDataPerDay
                    .stream()
                    .filter(a -> a.getUser().getUuid().equals(useruuid))
                    .mapToDouble(EmployeeDataPerDay::getNetAvailableHours).sum();
            System.out.println("availability = " + availability);

            double sum = budgetDocuments.stream().mapToDouble(BudgetDocument::getBudgetHours).sum();
            System.out.println("sum = " + sum);

            if(sum > availability) {
                for (BudgetDocument budgetDocument : budgetDocuments) {
                    double factor = budgetDocument.getBudgetHours() / sum;

                    budgetDocument.setBudgetHours(factor * availability);
                }
            }
        }
        return budgetDocumentList;
    }

    private BudgetDocument createBudgetDocumentV2(User user, LocalDate startDate, Contract contract, ContractConsultant userContract, List<Client> clientList) {
        BudgetDocument result = null;
        double budget = userContract.getHours() / 5.0; // (f.eks. 35 timer)
        if (budget != 0.0) {
            //double monthBudget = budget * DateUtils.getWeekdaysInPeriod(startDate, startDate.plusMonths(1)); // f.eks. 2019-12-01, 18 days / 5 = 3,6 weeks * 35 (budget) = 126 hours
            result = new BudgetDocument(startDate, getClient(clientList, contract), user, contract, budget, budget, userContract.getRate());
        }

        return result;
    }

    private BudgetDocument createBudgetDocument(User user, LocalDate startDate, Contract contract, ContractConsultant userContract, List<Client> clientList) {
        BudgetDocument result = null;
        double budget = userContract.getHours() / 5.0; // (f.eks. 35 timer)
        if (budget != 0.0) {
            double monthBudget = budget * getWeekdaysInPeriod(startDate, startDate.plusMonths(1)); // f.eks. 2019-12-01, 18 days / 5 = 3,6 weeks * 35 (budget) = 126 hours
            result = new BudgetDocument(startDate, getClient(clientList, contract), user, contract, monthBudget, monthBudget, userContract.getRate());
        }

        return result;
    }

    private Client getClient(List<Client> clientList, Contract contract) {
        return clientList.stream().filter(client -> client.getUuid().equals(contract.getClientuuid())).findAny().orElseThrow(() -> new RuntimeException(contract.toString()));
    }

}
