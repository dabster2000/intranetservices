package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.enterprise.context.control.ActivateRequestContext;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@JBossLog
@ApplicationScoped
public class BudgetCalculatingExecutor {

    private List<Client> clientList;
    private List<User> userList;

    @Inject
    ContractService contractService;


    @PostConstruct
    @ActivateRequestContext
    @Transactional
    void init() {
        userList = User.listAll();
        clientList = Client.listAll();
    }

    public void calcBudgetsV2(DateRangeMap dateRangeMap) {
        LocalDate startDate = dateRangeMap.getFromDate();
        LocalDate endDate = dateRangeMap.getEndDate();
        log.info("BudgetServiceCache.calcBudgets, lookupMonth = " + startDate);

        QuarkusTransaction.begin();
        EmployeeBudgetPerDayAggregate.delete("documentDate >= ?1 and documentDate < ?2", startDate, endDate);
        QuarkusTransaction.commit();

        List<Contract> contracts = contractService.findByPeriod(startDate, endDate);

        LocalDate lookupDate = startDate;
        List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = new ArrayList<>();
        do {
            if(DateUtils.isWeekend(lookupDate)) {
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

    /**
     * Recalculates and persists per-day budget aggregates for a single user on a specific day.
     *
     * Behavior:
     * - Deletes any existing EmployeeBudgetPerDayAggregate rows for the given user and document date.
     * - Skips weekends (no budgets are calculated for Saturdays and Sundays).
     * - Resolves active contracts for the user on that day, applies contract type discount modifiers,
     *   and builds EmployeeBudgetPerDayAggregate entries with 1/5 daily share of weekly hours.
     * - Adjusts the calculated list according to the user's availability (vacation, sickness, leave) for that day.
     * - Persists the resulting aggregates.
     *
     * Idempotency and transactions:
     * - Method is idempotent for the (user, day) key: it first removes old rows, then writes the new set.
     * - Executed within a JTA transaction due to @Transactional on the method.
     *
     * @param useruuid the UUID of the user to recalculate for
     * @param testDay  the day to recalculate (documentDate). Weekends are ignored.
     */
    @Transactional
    public void recalculateUserDailyBudgets(String useruuid, LocalDate testDay) {
        User user = User.findById(useruuid);
        if (user == null) {
            log.warnf("recalculateUserDailyBudgets: user not found, skipping. useruuid=%s day=%s", useruuid, String.valueOf(testDay));
            return;
        }
        EmployeeBudgetPerDayAggregate.delete("documentDate = ?1 and user = ?2", testDay, user);
        if(DateUtils.isWeekend(testDay)) return;

        List<Contract> contracts = contractService.findByDayReadOnly(testDay);
        List<Contract> activeContracts = contractService.getContractsByDate(contracts, user, testDay);

        List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregateList = new ArrayList<>();
        AtomicReference<Double> discountModifier = new AtomicReference<>(1.0);

        for (Contract contract : activeContracts) {
            ContractConsultant userContract = contract.findByUserAndDate(user, testDay);
            if(userContract == null) continue;
            if(userContract.getHours()==0.0) continue;

            discountModifier.set(1.0);
            if(contract.getContractTypeItems()!=null)
                contract.getContractTypeItems().forEach(cti -> discountModifier.updateAndGet(v -> (v - Double.parseDouble(cti.getValue()) / 100.0)));
            EmployeeBudgetPerDayAggregate employeeBudgetPerDayAggregate = new EmployeeBudgetPerDayAggregate(testDay, getClient(clientList, contract), user, contract, userContract.getHours() / 5.0, userContract.getHours() / 5.0, userContract.getRate() * discountModifier.get());

            if(employeeBudgetPerDayAggregate.getBudgetHours()==0.0) continue;
            employeeBudgetPerDayAggregateList.add(employeeBudgetPerDayAggregate);
        }
        List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = new ArrayList<>(adjustForAvailabilityByUser(user, employeeBudgetPerDayAggregateList, testDay));

        //QuarkusTransaction.requiringNew().run(() ->
        EmployeeBudgetPerDayAggregate.persist(employeeBudgetPerDayAggregates);
        //);
    }

    public void calcUserBudgets(String useruuid) {
        LocalDate startDate = DateUtils.getCompanyStartDate();
        LocalDate endDate = DateUtils.getCurrentFiscalStartDate().plusYears(1);
        User user = User.findById(useruuid);
        log.info("BudgetServiceCache.calcBudgetsV4, lookupMonth = " + startDate + " for user " + user.getUsername());
        QuarkusTransaction.begin();
        EmployeeBudgetPerDayAggregate.delete("documentDate >= ?1 and documentDate < ?2 and user = ?3", startDate, endDate, user);
        QuarkusTransaction.commit();

        List<Client> clientList = Client.listAll();
        List<Contract> contracts = contractService.findByPeriod(startDate, endDate);

        LocalDate lookupDate = startDate;
        List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = new ArrayList<>();
        do {
            if(DateUtils.isWeekend(lookupDate)) {
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

    private List<EmployeeBudgetPerDayAggregate> adjustForAvailability(List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregateList, LocalDate lookupMonth) {
        List<BiDataPerDay> employeeAvailabilityPerDayAggregate = BiDataPerDay.list("documentDate = ?1 AND consultantType IN ('CONSULTANT', 'STUDENT')", lookupMonth);
        List<String> userList = employeeBudgetPerDayAggregateList.stream().map(b -> b.getUser().getUuid()).distinct().toList();
        for (String useruuid : userList) {
            List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = employeeBudgetPerDayAggregateList
                    .stream()
                    .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(useruuid) && budgetDocument.getDocumentDate().isEqual(lookupMonth))
                    .toList();
            BiDataPerDay employeeData = employeeAvailabilityPerDayAggregate
                    .stream()
                    .filter(a -> a.getUser().getUuid().equals(useruuid)).findFirst().orElse(new BiDataPerDay(new Company()));

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

    private List<EmployeeBudgetPerDayAggregate> adjustForAvailabilityByUser(User user, List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregateList, LocalDate lookupMonth) {
        List<BiDataPerDay> employeeAvailabilityPerDayAggregate = BiDataPerDay.list("documentDate = ?1 AND user = ?2 AND consultantType IN ('CONSULTANT', 'STUDENT')", lookupMonth, user);
        //List<String> userList = employeeBudgetPerDayAggregateList.stream().map(b -> b.getUser().getUuid()).distinct().toList();
        List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = employeeBudgetPerDayAggregateList
                .stream()
                .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(user.getUuid()) && budgetDocument.getDocumentDate().isEqual(lookupMonth))
                .toList();
        BiDataPerDay employeeData = employeeAvailabilityPerDayAggregate
                .stream()
                .filter(a -> a.getUser().getUuid().equals(user.getUuid())).findFirst().orElse(new BiDataPerDay());

        double sum = employeeBudgetPerDayAggregates.stream().mapToDouble(EmployeeBudgetPerDayAggregate::getBudgetHours).sum();

        for (EmployeeBudgetPerDayAggregate employeeBudgetPerDayAggregate : employeeBudgetPerDayAggregates) {
            employeeBudgetPerDayAggregate.setCompany(employeeData.getCompany());
            if(sum > employeeData.getNetAvailableHours()) {
                double factor = employeeBudgetPerDayAggregate.getBudgetHours() / sum;
                employeeBudgetPerDayAggregate.setBudgetHours(factor * employeeData.getNetAvailableHours());
            }
        }
        return employeeBudgetPerDayAggregateList;
    }

    private Client getClient(List<Client> clientList, Contract contract) {
        return clientList.stream().filter(client -> client.getUuid().equals(contract.getClientuuid())).findAny().orElseThrow(() -> new RuntimeException(contract.toString()));
    }

}
