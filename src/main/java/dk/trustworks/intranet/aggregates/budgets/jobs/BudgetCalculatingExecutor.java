package dk.trustworks.intranet.aggregates.budgets.jobs;

import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerDayAggregate;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@JBossLog
@ApplicationScoped
public class BudgetCalculatingExecutor {

    @Inject
    UserService userService;

    @Inject
    ClientService clientService;

    @Inject
    ContractService contractService;

    public void calcBudgetsV2(DateRangeMap dateRangeMap) {
        LocalDate startDate = dateRangeMap.getFromDate();
        LocalDate endDate = dateRangeMap.getEndDate();
        log.info("BudgetServiceCache.calcBudgets, lookupMonth = " + startDate);
        QuarkusTransaction.begin();
        EmployeeBudgetPerDayAggregate.delete("documentDate >= ?1 and documentDate < ?2", startDate, endDate);
        QuarkusTransaction.commit();

        List<Client> clientList = clientService.listAllClients();
        List<User> userList = userService.listAll(true);
        List<Contract> contracts = contractService.findByPeriod(startDate, endDate);

        LocalDate lookupDate = startDate;
        List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = new ArrayList<>();
        do {
            if(!DateUtils.isWorkday(lookupDate)) {
                lookupDate = lookupDate.plusDays(1);
                continue;
            }
            for (User user : userList) {
                List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregateList = new ArrayList<>();
                List<Contract> activeContracts = contractService.getContractsByDate(contracts, user, lookupDate);
                for (Contract contract : activeContracts) {
                    ContractConsultant userContract = contract.findByUserAndDate(user, lookupDate);
                    if(userContract == null) continue;
                    if(userContract.getHours()==0.0) continue;

                    EmployeeBudgetPerDayAggregate employeeBudgetPerDayAggregate = new EmployeeBudgetPerDayAggregate(lookupDate, getClient(clientList, contract), user, contract, userContract.getHours() / 5.0, userContract.getHours() / 5.0, userContract.getRate());

                    if(employeeBudgetPerDayAggregate.getBudgetHours()==0.0) continue;
                    employeeBudgetPerDayAggregateList.add(employeeBudgetPerDayAggregate);
                }
                employeeBudgetPerDayAggregates.addAll(adjustForAvailability(employeeBudgetPerDayAggregateList, lookupDate));
            }
            lookupDate = lookupDate.plusDays(1);
        } while (lookupDate.isBefore(endDate));
        QuarkusTransaction.begin();
        EmployeeBudgetPerDayAggregate.persist(employeeBudgetPerDayAggregates);
        QuarkusTransaction.commit();
    }

    private List<EmployeeBudgetPerDayAggregate> adjustForAvailability(List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregateList, LocalDate lookupMonth) {
        List<EmployeeAvailabilityPerDayAggregate> employeeAvailabilityPerDayAggregate = EmployeeAvailabilityPerDayAggregate.<EmployeeAvailabilityPerDayAggregate>stream("documentDate = ?1 AND consultantType = 'CONSULTANT'", lookupMonth).toList();
        List<String> userList = employeeBudgetPerDayAggregateList.stream().map(b -> b.getUser().getUuid()).distinct().toList();
        for (String useruuid : userList) {
            List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = employeeBudgetPerDayAggregateList
                    .stream()
                    .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(useruuid) && budgetDocument.getDocumentDate().isEqual(lookupMonth))
                    .toList();
            EmployeeAvailabilityPerDayAggregate employeeData = employeeAvailabilityPerDayAggregate
                    .stream()
                    .filter(a -> a.getUser().getUuid().equals(useruuid)).findFirst().orElse(new EmployeeAvailabilityPerDayAggregate());

            double sum = employeeBudgetPerDayAggregates.stream().mapToDouble(EmployeeBudgetPerDayAggregate::getBudgetHours).sum();

            for (EmployeeBudgetPerDayAggregate employeeBudgetPerDayAggregate : employeeBudgetPerDayAggregates) {
                employeeBudgetPerDayAggregate.setCompany(employeeData.getCompany());
                if(sum > employeeData.getNetAvailableHours()) {
                    double factor = employeeBudgetPerDayAggregate.getBudgetHours() / sum;
                    employeeBudgetPerDayAggregate.setBudgetHours(factor * employeeData.getNetAvailableHours());
                }
            }
        }
        return employeeBudgetPerDayAggregateList;
    }

    private Client getClient(List<Client> clientList, Contract contract) {
        return clientList.stream().filter(client -> client.getUuid().equals(contract.getClientuuid())).findAny().orElseThrow(() -> new RuntimeException(contract.toString()));
    }

}
