package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.utils.DateUtils;
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

    @Inject
    ContractService contractService;

    @PostConstruct
    @ActivateRequestContext
    @Transactional
    void init() {
        clientList = Client.listAll();
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
            if(contract.getContractTypeItems()!=null) {
                contract.getContractTypeItems().forEach(cti -> {
                    if (cti.getValue() != null && !cti.getValue().trim().isEmpty()) {
                        try {
                            discountModifier.updateAndGet(v -> (v - Double.parseDouble(cti.getValue()) / 100.0));
                        } catch (NumberFormatException e) {
                            log.warnf("Invalid discount value '%s' for contract %s, skipping", cti.getValue(), contract.getUuid());
                        }
                    }
                });
            }
            EmployeeBudgetPerDayAggregate employeeBudgetPerDayAggregate = new EmployeeBudgetPerDayAggregate(testDay, getClient(clientList, contract), user, contract, userContract.getHours() / 5.0, userContract.getHours() / 5.0, userContract.getRate() * discountModifier.get());

            if(employeeBudgetPerDayAggregate.getBudgetHours()==0.0) continue;
            employeeBudgetPerDayAggregateList.add(employeeBudgetPerDayAggregate);
        }
        List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = new ArrayList<>(adjustForAvailabilityByUser(user, employeeBudgetPerDayAggregateList, testDay));

        //QuarkusTransaction.requiringNew().run(() ->
        EmployeeBudgetPerDayAggregate.persist(employeeBudgetPerDayAggregates);
        //);
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
