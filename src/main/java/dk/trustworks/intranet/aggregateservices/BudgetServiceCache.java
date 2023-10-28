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
import dk.trustworks.intranet.dto.EmployeeDataPerMonth;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    BudgetService budgetAPI;

    public List<BudgetDocument> findAllBudgetData() {
        return BudgetDocument.listAll();
    }

    public List<BudgetDocument> findAllBudgetDataByUserAndPeriod(String useruuid, LocalDate startDate, LocalDate endDate) {
        return BudgetDocument.find("useruuid like ?1 and month >= ?2 and month < ?3", useruuid, startDate, endDate).list();
    }

    public void calcBudgets(DateRangeMap dateRangeMap) {
        LocalDate startDate = dateRangeMap.getFromDate();
        log.info("BudgetServiceCache.calcBudgets, lookupMonth = " + startDate);
        QuarkusTransaction.requiringNew().run(() -> {
            BudgetDocument.delete("month >= ?1 and month < ?2", startDate, startDate.plusMonths(1));
        });
        List<Client> clientList = clientService.listAllClients();
        List<User> userList = userService.listAll(true);
        List<Budget> budgets = budgetAPI.findByMonthAndYear(startDate);
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
        //LocalDate lookupMonth;
        List<EmployeeDataPerMonth> availabilityByUser = EmployeeDataPerMonth.<EmployeeDataPerMonth>stream("year = ?1 AND month = ?2 AND consultantType = 'CONSULTANT' AND status != 'TERMINATED'", lookupMonth.getYear(), lookupMonth.getMonthValue()).toList();
        List<String> userList = budgetDocumentList.stream().map(b -> b.getUser().getUuid()).distinct().toList();
        for (String useruuid : userList) {
            List<BudgetDocument> budgetDocuments = budgetDocumentList
                    .stream()
                    .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(useruuid) && budgetDocument.getMonth().isEqual(lookupMonth.withDayOfMonth(1)))
                    .toList();
            Optional<EmployeeDataPerMonth> availability = availabilityByUser
                    .stream()
                    .filter(a -> a.getUseruuid().equals(useruuid))
                    .findAny();
            if(availability.isEmpty()) continue;

            double sum = budgetDocuments.stream().mapToDouble(BudgetDocument::getBudgetHours).sum();

            if(sum > availability.get().getNetAvailableHours()) {
                for (BudgetDocument budgetDocument : budgetDocuments) {
                    double factor = budgetDocument.getBudgetHours() / sum;

                    budgetDocument.setBudgetHours(factor * availability.get().getNetAvailableHours());
                }
            }
        }
        return budgetDocumentList;
    }

    private BudgetDocument createBudgetDocument(User user, LocalDate startDate, Contract contract, ContractConsultant userContract, List<Client> clientList) {
        BudgetDocument result = null;
        double budget = userContract.getHours() / 5.0; // (f.eks. 35 timer)
        if (budget != 0.0) {
            double monthBudget = budget * DateUtils.getWeekdaysInPeriod(startDate, startDate.plusMonths(1)); // f.eks. 2019-12-01, 18 days / 5 = 3,6 weeks * 35 (budget) = 126 hours
            result = new BudgetDocument(startDate, getClient(clientList, contract), user, contract, monthBudget, monthBudget, userContract.getRate());
        }

        return result;
    }

    private Client getClient(List<Client> clientList, Contract contract) {
        return clientList.stream().filter(client -> client.getUuid().equals(contract.getClientuuid())).findAny().orElseThrow(() -> new RuntimeException(contract.toString()));
    }

}
