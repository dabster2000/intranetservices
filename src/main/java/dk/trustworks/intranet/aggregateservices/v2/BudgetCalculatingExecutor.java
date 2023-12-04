package dk.trustworks.intranet.aggregateservices.v2;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.aggregateservices.model.v2.EmployeeBudgetPerDay;
import dk.trustworks.intranet.aggregateservices.model.v2.EmployeeBudgetPerMonth;
import dk.trustworks.intranet.aggregateservices.model.v2.EmployeeDataPerDay;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
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

@JBossLog
@ApplicationScoped
public class BudgetCalculatingExecutor {

    @Inject
    UserService userService;

    @Inject
    ClientService clientService;

    @Inject
    ContractService contractService;

    public List<EmployeeBudgetPerDay> findAllBudgetData() {
        return EmployeeBudgetPerMonth.<EmployeeBudgetPerMonth>listAll().stream().map(bdm -> new EmployeeBudgetPerDay(LocalDate.of(bdm.getYear(), bdm.getMonth(), 1), bdm.getClient(), bdm.getUser(), bdm.getContract(), bdm.getBudgetHours(), bdm.getBudgetHours(), bdm.getRate())).toList();
    }

    public List<EmployeeBudgetPerDay> findAllBudgetDataByUserAndPeriod(String useruuid, LocalDate startDate, LocalDate endDate) {
        return EmployeeBudgetPerMonth.<EmployeeBudgetPerMonth>list("STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') " +
                "      BETWEEN STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') " +
                "      AND STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and useruuid = ?5", startDate.getYear(), startDate.getMonthValue(), endDate.getYear(), endDate.getMonthValue(), useruuid)
                .stream().map(bdm -> new EmployeeBudgetPerDay(LocalDate.of(bdm.getYear(), bdm.getMonth(), 1), bdm.getClient(), bdm.getUser(), bdm.getContract(), bdm.getBudgetHours(), bdm.getBudgetHours(), bdm.getRate())).toList();
    }

    public void calcBudgetsV2(DateRangeMap dateRangeMap) {
        LocalDate startDate = dateRangeMap.getFromDate();
        LocalDate endDate = dateRangeMap.getEndDate();
        log.info("BudgetServiceCache.calcBudgets, lookupMonth = " + startDate);
        QuarkusTransaction.begin();
        EmployeeBudgetPerDay.delete("documentDate >= ?1 and documentDate < ?2", startDate, endDate);
        QuarkusTransaction.commit();

        List<Client> clientList = clientService.listAllClients();
        List<User> userList = userService.listAll(true);
        List<Contract> contracts = contractService.findByPeriod(startDate, endDate);

        LocalDate lookupDate = startDate;
        List<EmployeeBudgetPerDay> employeeBudgetPerDays = new ArrayList<>();
        do {
            if(!DateUtils.isWorkday(lookupDate)) {
                lookupDate = lookupDate.plusDays(1);
                continue;
            }
            for (User user : userList) {
                List<EmployeeBudgetPerDay> employeeBudgetPerDayList = new ArrayList<>();
                List<Contract> activeContracts = contractService.getContractsByDate(contracts, user, lookupDate);
                for (Contract contract : activeContracts) {
                    ContractConsultant userContract = contract.findByUserAndDate(user, lookupDate);
                    if(userContract == null) continue;
                    if(userContract.getHours()==0.0) continue;

                    EmployeeBudgetPerDay employeeBudgetPerDay = new EmployeeBudgetPerDay(lookupDate, getClient(clientList, contract), user, contract, userContract.getHours() / 5.0, userContract.getHours() / 5.0, userContract.getRate());

                    if(employeeBudgetPerDay.getBudgetHours()==0.0) continue;
                    employeeBudgetPerDayList.add(employeeBudgetPerDay);
                }
                employeeBudgetPerDays.addAll(adjustForAvailability(employeeBudgetPerDayList, lookupDate));
            }
            lookupDate = lookupDate.plusDays(1);
        } while (lookupDate.isBefore(endDate));
        QuarkusTransaction.begin();
        EmployeeBudgetPerDay.persist(employeeBudgetPerDays);
        QuarkusTransaction.commit();
    }

    private List<EmployeeBudgetPerDay> adjustForAvailability(List<EmployeeBudgetPerDay> employeeBudgetPerDayList, LocalDate lookupMonth) {
        List<EmployeeDataPerDay> employeeDataPerDay = EmployeeDataPerDay.<EmployeeDataPerDay>stream("documentDate = ?1 AND consultantType = 'CONSULTANT'", lookupMonth).toList();
        List<String> userList = employeeBudgetPerDayList.stream().map(b -> b.getUser().getUuid()).distinct().toList();
        for (String useruuid : userList) {
            List<EmployeeBudgetPerDay> employeeBudgetPerDays = employeeBudgetPerDayList
                    .stream()
                    .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(useruuid) && budgetDocument.getDocumentDate().isEqual(lookupMonth))
                    .toList();
            EmployeeDataPerDay employeeData = employeeDataPerDay
                    .stream()
                    .filter(a -> a.getUser().getUuid().equals(useruuid)).findFirst().orElse(new EmployeeDataPerDay());

            double sum = employeeBudgetPerDays.stream().mapToDouble(EmployeeBudgetPerDay::getBudgetHours).sum();

            for (EmployeeBudgetPerDay employeeBudgetPerDay : employeeBudgetPerDays) {
                employeeBudgetPerDay.setCompany(employeeData.getCompany());
                if(sum > employeeData.getNetAvailableHours()) {
                    double factor = employeeBudgetPerDay.getBudgetHours() / sum;
                    employeeBudgetPerDay.setBudgetHours(factor * employeeData.getNetAvailableHours());
                }
            }
        }
        return employeeBudgetPerDayList;
    }

    private Client getClient(List<Client> clientList, Contract contract) {
        return clientList.stream().filter(client -> client.getUuid().equals(contract.getClientuuid())).findAny().orElseThrow(() -> new RuntimeException(contract.toString()));
    }

}
