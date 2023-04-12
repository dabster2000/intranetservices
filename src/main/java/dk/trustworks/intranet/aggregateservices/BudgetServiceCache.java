package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.contracts.model.Budget;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dto.AvailabilityDocument;
import dk.trustworks.intranet.dto.BudgetDocument;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.services.UserService;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.scheduler.Scheduled;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.transaction.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@JBossLog
@ApplicationScoped
public class BudgetServiceCache {

    @Inject
    UserService userService;

    @Inject
    AvailabilityService availabilityService;

    @Inject
    ClientService clientService;

    @Inject
    ContractService contractService;

    @Inject
    BudgetService budgetAPI;

    @Inject
    TransactionManager transactionManager;

    @Inject
    EntityManager em;

    @Scheduled(every = "1h", delay = 10000)
    public void refreshBudgetData() {
        //List<BudgetDocument> budgetDocumentList = new ArrayList<>();

        try {
            transactionManager.begin();
            //em.createNativeQuery("truncate table budget_document");
            BudgetDocument.deleteAll();
            transactionManager.commit();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }


        LocalDate lookupMonth = LocalDate.of(2014, 7, 1);
        do {
            try {
                transactionManager.begin();
                //budgetDocumentList.addAll(calcBudgets(lookupMonth));;
                BudgetDocument.persist(calcBudgets(lookupMonth));
                transactionManager.commit();
            } catch (NotSupportedException | HeuristicRollbackException | HeuristicMixedException | RollbackException |
                     SystemException e) {
                throw new RuntimeException(e);
            }

            lookupMonth = lookupMonth.plusMonths(1);
        } while (lookupMonth.isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(2)));
    }

    //@CacheResult(cacheName = "budget-cache")
    public List<BudgetDocument> createBudgetData() {
        return BudgetDocument.listAll();
    }

    public List<BudgetDocument> calcBudgets(LocalDate lookupMonth) {
        List<Client> clientList = clientService.listAll();
        List<User> userList = userService.listAll(true);
        List<Budget> budgets = budgetAPI.findByMonthAndYear(lookupMonth);
        List<Contract> contracts = contractService.findByPeriod(lookupMonth, lookupMonth.plusMonths(1));
        List<BudgetDocument> budgetDocumentList = new ArrayList<>();
        for (User user : userList) {
            List<Contract> activeContracts = contractService.getContractsByDate(contracts, user, lookupMonth);
            for (Contract contract : activeContracts) {
                if(contract.getContractType().equals(ContractType.PERIOD)) {
                    ContractConsultant userContract = contract.findByUser(user);
                    if(userContract == null) continue;

                    BudgetDocument budgetDocument = createBudgetDocument(user, lookupMonth, contract, userContract, clientList);
                    if (budgetDocument == null) {
                        continue;
                    }
                    budgetDocumentList.add(budgetDocument);
                } else {
                    ContractConsultant userContract = contract.findByUser(user);
                    if(userContract == null || userContract.getRate() == 0.0) continue;
                    double budget = budgets.stream()
                            .filter(budgetNew ->
                                    budgetNew.getConsultantuuid().equals(userContract.getUuid()) &&
                                            budgetNew.getYear() == lookupMonth.getYear() &&
                                            (budgetNew.getMonth()+1) == lookupMonth.getMonthValue())
                            .mapToDouble(budgetNew -> budgetNew.getBudget() / userContract.getRate()).sum();

                BudgetDocument budgetDocument = new BudgetDocument(lookupMonth, getClient(clientList, contract), user, contract, budget, budget, contract.findByUser(user).getRate());
                    budgetDocumentList.add(budgetDocument);
                }
            }
        }
        return adjustForAvailability(budgetDocumentList, userList, lookupMonth);
    }

    private List<BudgetDocument> adjustForAvailability(List<BudgetDocument> budgetDocumentList, List<User> userList, LocalDate lookupMonth) {
        //LocalDate lookupMonth;
        for (User user : userList) {
            //lookupMonth = LocalDate.of(2014, 7, 1);
            //do {
                //LocalDate finalStartDate = lookupMonth;
                List<BudgetDocument> budgetDocuments = budgetDocumentList.stream()
                        .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(user.getUuid()) && budgetDocument.getMonth().isEqual(lookupMonth.withDayOfMonth(1))).toList();
                AvailabilityDocument availability = availabilityService.getConsultantAvailabilityByMonth(user.getUuid(), lookupMonth);
                if(availability==null) continue;
                //if(user.getUsername().equalsIgnoreCase("hans.lassen")) System.out.println("7: availability = " + availability);

                double sum = budgetDocuments.stream().mapToDouble(BudgetDocument::getBudgetHours).sum();
                //if(user.getUsername().equalsIgnoreCase("hans.lassen")) System.out.println("8: sum = " + sum);

                if(sum > availability.getNetAvailableHours()) {
                    for (BudgetDocument budgetDocument : budgetDocuments) {
                        double factor = budgetDocument.getBudgetHours() / sum;

                        budgetDocument.setBudgetHours(factor * availability.getNetAvailableHours());
                        //if(user.getUsername().equalsIgnoreCase("hans.lassen")) System.out.println("9: budgetDocument = " + budgetDocument);
                    }
                }

                //lookupMonth = lookupMonth.plusMonths(1);
            //} while (lookupMonth.isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(2)));
        }
        return budgetDocumentList;
    }

    private BudgetDocument createBudgetDocument(User user, LocalDate startDate, Contract contract, ContractConsultant userContract, List<Client> clientList) {
        //System.out.println("BudgetServiceCache.createBudgetDocument");
        //System.out.println("user = " + user + ", startDate = " + startDate + ", contract = " + contract + ", userContract = " + userContract + ", clientList = " + clientList);
        BudgetDocument result = null;
        double budget = userContract.getHours(); // (f.eks. 35 timer)
        if (budget != 0.0) {
            AvailabilityDocument availability = availabilityService.getConsultantAvailabilityByMonth(user.getUuid(), startDate);
            //if(user.getUsername().equalsIgnoreCase("hans.lassen")) System.out.println("12: availability = " + availability);
            double monthBudget = budget * availability.getWeeks(); // f.eks. 2019-12-01, 18 days / 5 = 3,6 weeks * 35 (budget) = 126 hours
            result = new BudgetDocument(startDate, getClient(clientList, contract), user, contract, monthBudget, monthBudget, userContract.getRate());
        }

        return result;
    }

    private Client getClient(List<Client> clientList, Contract contract) {
        return clientList.stream().filter(client -> client.getUuid().equals(contract.getClientuuid())).findAny().orElseThrow(() -> new RuntimeException(contract.toString()));
    }

}
