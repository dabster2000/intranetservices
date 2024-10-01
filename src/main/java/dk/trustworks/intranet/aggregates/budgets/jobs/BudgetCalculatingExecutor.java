package dk.trustworks.intranet.aggregates.budgets.jobs;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.bidata.repositories.BiDataPerDayRepository;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
    BiDataPerDayRepository biDataPerDayRepository;

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

                    AtomicReference<Double> discountModifier = new AtomicReference<>(1.0);
                    if(contract.getContractTypeItems()!=null)
                        contract.getContractTypeItems().forEach(cti -> discountModifier.updateAndGet(v -> (v - Double.parseDouble(cti.getValue()) / 100.0)));
                    EmployeeBudgetPerDayAggregate employeeBudgetPerDayAggregate = new EmployeeBudgetPerDayAggregate(lookupDate, getClient(clientList, contract), user, contract, userContract.getHours() / 5.0, userContract.getHours() / 5.0, userContract.getRate() * discountModifier.get());

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

    public void calcBudgetsV4(String useruuid, DateRangeMap dateRangeMap) {
        LocalDate startDate = dateRangeMap.getFromDate();
        LocalDate endDate = dateRangeMap.getEndDate();
        User user = User.findById(useruuid);
        log.info("BudgetServiceCache.calcBudgets, lookupMonth = " + startDate + " for user " + user.getUsername());
        QuarkusTransaction.begin();
        EmployeeBudgetPerDayAggregate.delete("documentDate >= ?1 and documentDate < ?2 and user = ?3", startDate, endDate, user);
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
            List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregateList = new ArrayList<>();
            List<Contract> activeContracts = contractService.getContractsByDate(contracts, user, lookupDate);
            for (Contract contract : activeContracts) {
                ContractConsultant userContract = contract.findByUserAndDate(user, lookupDate);
                if(userContract == null) continue;
                if(userContract.getHours()==0.0) continue;

                AtomicReference<Double> discountModifier = new AtomicReference<>(1.0);
                if(contract.getContractTypeItems()!=null)
                    contract.getContractTypeItems().forEach(cti -> discountModifier.updateAndGet(v -> (v - Double.parseDouble(cti.getValue()) / 100.0)));
                EmployeeBudgetPerDayAggregate employeeBudgetPerDayAggregate = new EmployeeBudgetPerDayAggregate(lookupDate, getClient(clientList, contract), user, contract, userContract.getHours() / 5.0, userContract.getHours() / 5.0, userContract.getRate() * discountModifier.get());

                if(employeeBudgetPerDayAggregate.getBudgetHours()==0.0) continue;
                employeeBudgetPerDayAggregateList.add(employeeBudgetPerDayAggregate);
            }
            employeeBudgetPerDayAggregates.addAll(adjustForAvailability(employeeBudgetPerDayAggregateList, lookupDate));

            lookupDate = lookupDate.plusDays(1);
        } while (lookupDate.isBefore(endDate));
        QuarkusTransaction.begin();
        EmployeeBudgetPerDayAggregate.persist(employeeBudgetPerDayAggregates);
        QuarkusTransaction.commit();
    }

    public void calcBudgetsV3(String useruuid, DateRangeMap dateRangeMap) {
        final LocalDate startDate = dateRangeMap.getFromDate();
        final LocalDate endDate = dateRangeMap.getEndDate();
        log.info("BudgetServiceCache.calcBudgets, lookupMonth = " + startDate);

        List<Client> clientList = clientService.listAllClients();
        User user = userService.findById(useruuid, true);
        List<Contract> contracts = contractService.findByPeriod(startDate, endDate);

        LocalDate lookupDate = startDate;
        List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = new ArrayList<>();
        do {
            if(!DateUtils.isWorkday(lookupDate)) {
                lookupDate = lookupDate.plusDays(1);
                continue;
            }
            List<Contract> activeContracts = contractService.getContractsByDate(contracts, user, lookupDate);
            for (Contract contract : activeContracts) {
                ContractConsultant userContract = contract.findByUserAndDate(user, lookupDate);
                if(userContract == null) continue;
                if(userContract.getHours()==0.0) continue;

                AtomicReference<Double> discountModifier = new AtomicReference<>(1.0);
                if(contract.getContractTypeItems()!=null)
                    contract.getContractTypeItems().forEach(cti -> discountModifier.updateAndGet(v -> (v - Double.parseDouble(cti.getValue()) / 100.0)));
                EmployeeBudgetPerDayAggregate employeeBudgetPerDayAggregate = new EmployeeBudgetPerDayAggregate(lookupDate, getClient(clientList, contract), user, contract, userContract.getHours() / 5.0, userContract.getHours() / 5.0, userContract.getRate() * discountModifier.get());

                if(employeeBudgetPerDayAggregate.getBudgetHours()==0.0) continue;
                employeeBudgetPerDayAggregates.add(employeeBudgetPerDayAggregate);
            }
            lookupDate = lookupDate.plusDays(1);
        } while (lookupDate.isBefore(endDate));

        QuarkusTransaction.requiringNew().run(() -> {
            LocalDate testDate = startDate;
            do {
                LocalDate finalLookupDate = testDate;
                double budgetHours = employeeBudgetPerDayAggregates.stream().filter(b -> b.getDocumentDate().isEqual(finalLookupDate)).mapToDouble(EmployeeBudgetPerDayAggregate::getBudgetHours).sum();
                update(useruuid, testDate.toString(), testDate.getYear(), testDate.getMonthValue(), testDate.getDayOfMonth(), BigDecimal.valueOf(budgetHours));
                testDate = testDate.plusDays(1);
            } while (testDate.isBefore(endDate));
        });
    }

    public void update(String userUuid, String documentDate, int year, int month, int day, BigDecimal budgetHours) {
        biDataPerDayRepository.insertOrUpdateBudgetHours(userUuid, documentDate, year, month, day, budgetHours);
    }

    private List<EmployeeBudgetPerDayAggregate> adjustForAvailability(List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregateList, LocalDate lookupMonth) {
        List<BiDataPerDay> employeeAvailabilityPerDayAggregate = BiDataPerDay.<BiDataPerDay>list("documentDate = ?1 AND consultantType IN ('CONSULTANT', 'STUDENT')", lookupMonth);
        List<String> userList = employeeBudgetPerDayAggregateList.stream().map(b -> b.getUser().getUuid()).distinct().toList();
        for (String useruuid : userList) {
            List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = employeeBudgetPerDayAggregateList
                    .stream()
                    .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(useruuid) && budgetDocument.getDocumentDate().isEqual(lookupMonth))
                    .toList();
            BiDataPerDay employeeData = employeeAvailabilityPerDayAggregate
                    .stream()
                    .filter(a -> a.getUser().getUuid().equals(useruuid)).findFirst().orElse(new BiDataPerDay());

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
