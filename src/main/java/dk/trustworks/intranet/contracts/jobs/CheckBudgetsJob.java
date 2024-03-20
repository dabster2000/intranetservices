package dk.trustworks.intranet.contracts.jobs;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Deprecated
@ApplicationScoped
public class CheckBudgetsJob {
/*
    @Inject
    WorkService workService;

    @Inject
    ContractService contractService;

    @Inject
    BudgetService budgetService;

    //@Scheduled(cron = "0 1 * * *")
    //@Scheduled(every = "10m")

    public void updateEndOfMonthBudgetsJob() {
        LocalDate startDate = LocalDate.of(2014, 2, 1);
        LocalDate endDate = LocalDate.now().withDayOfMonth(1);

        do {
            List<Budget> budgetList = new ArrayList<>();
            List<WorkFull> workList = workService.findByPeriod(startDate, startDate.plusMonths(1));

            for (WorkFull work : workList) {
                if(
                        work.getRegistered().isBefore(startDate) ||
                                work.getRegistered().isAfter(startDate.plusMonths(1).minusDays(1)) ||
                                work.getRate() == 0.0 ||
                                work.getContractuuid() == null ||
                                work.getContractuuid().isEmpty()
                ) continue;
                Optional<Contract> optionalContract = Contract.findByIdOptional(work.getContractuuid());
                if(optionalContract.isPresent() && !optionalContract.get().getContractType().equals(ContractType.AMOUNT)) continue;
                Optional<ContractConsultant> consultantOptional = contractService.getContractConsultants(work.getContractuuid()).stream().filter(contractConsultant -> contractConsultant.getUseruuid().equals(work.getUseruuid())).findFirst();
                if(consultantOptional.isEmpty()) continue;

                Optional<Budget> optionalBudget = budgetList.stream().filter(b ->
                        b.getProjectuuid().equals(work.getProjectuuid()) &&
                                b.getConsultantuuid().equals(consultantOptional.get().getUuid()) &&
                                b.getYear() == work.getRegistered().getYear() &&
                                b.getMonth() == work.getRegistered().getMonthValue()).findFirst();
                Budget budget = optionalBudget.orElseGet(() -> {
                    Budget b = new Budget(UUID.randomUUID().toString(),
                            work.getRegistered().getMonthValue() - 1,
                            work.getRegistered().getYear(),
                            0.0,
                            consultantOptional.get().getUuid(),
                            work.getProjectuuid());
                    budgetList.add(b);
                    return b;
                });
                budget.setBudget(budget.getBudget()+work.getWorkduration());
            }
            budgetService.replaceMonthBudgets(startDate.getYear(), startDate.getMonthValue(), budgetList);

            startDate = startDate.plusMonths(1);
        } while (startDate.isBefore(endDate));
    }

     */
}
