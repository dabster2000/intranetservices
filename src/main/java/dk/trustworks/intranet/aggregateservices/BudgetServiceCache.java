package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.contracts.model.Budget;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dto.AvailabilityDocument;
import dk.trustworks.intranet.dto.BudgetDocument;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.messaging.emitters.MessageEmitter;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
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
    SlackService slackService;

    @Inject
    MessageEmitter messageEmitter;

    /*
    @Scheduled(every = "3h", delay = 1)
    public void refreshBudgetData() {
        System.out.println("prnt - BudgetServiceCache.refreshBudgetData");
        log.info("BudgetServiceCache.refreshBudgetData");

        log.info("Creating all budgets...");
        long l = System.currentTimeMillis();
        LocalDate lookupMonth = LocalDate.of(2024, 7, 1);
        do {
            try {
                QuarkusTransaction.begin();
                messageEmitter.sendBudgetUpdateMonthEvent(new DateRangeMap(lookupMonth, lookupMonth.plusMonths(1)));
                QuarkusTransaction.commit();
            } catch (Exception e) {
                try {
                    log.error(e);
                    slackService.sendMessage(userService.findByUsername("hans.lassen", true), ExceptionUtils.getStackTrace(e));
                } catch (SlackApiException | IOException ex) {
                    throw new RuntimeException(ex);
                }
                throw new RuntimeException(e);
            }

            lookupMonth = lookupMonth.minusMonths(1);
        } while (lookupMonth.isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(2)));
        log.info("...budgets created: "+(System.currentTimeMillis()-l));
    }

     */

    //@CacheResult(cacheName = "budget-cache")
    public List<BudgetDocument> findAllBudgetData() {
        return BudgetDocument.listAll();
    }

    public List<BudgetDocument> findAllBudgetDataByUserAndPeriod(String useruuid, LocalDate startDate, LocalDate endDate) {
        return BudgetDocument.find("useruuid like ?1 and month >= ?2 and month < ?3", useruuid, startDate, endDate).list();
    }

    public void calcBudgets(DateRangeMap dateRangeMap) {
        LocalDate startDate = dateRangeMap.getFromDate();
        log.info("BudgetServiceCache.calcBudgets, lookupMonth = " + startDate);
        QuarkusTransaction.begin();
        BudgetDocument.delete("month >= ?1 and month < ?2", startDate, startDate.plusMonths(1));
        QuarkusTransaction.commit();
        List<Client> clientList = clientService.listAllClients();
        //Uni<List<Client>> clientsListUni = clientService.listAllClients().collect().asList();
        //clientList.forEach(client -> {
            List<User> userList = userService.listAll(true);
            List<Budget> budgets = budgetAPI.findByMonthAndYear(startDate);
            List<Contract> contracts = contractService.findByPeriod(startDate, startDate.plusMonths(1));
            List<BudgetDocument> budgetDocumentList = new ArrayList<>();
            QuarkusTransaction.begin();
            for (User user : userList) {
                //if(user.getUsername().equals("hans.lassen")) System.out.println("user.getUsername() = " + user.getUsername());
                List<Contract> activeContracts = contractService.getContractsByDate(contracts, user, startDate);
                for (Contract contract : activeContracts) {
                    //if(user.getUsername().equals("hans.lassen")) System.out.println("contract = " + contract);
                    if(contract.getContractType().equals(ContractType.PERIOD)) {
                        //if(user.getUsername().equals("hans.lassen")) System.out.println("PERIOD");
                        ContractConsultant userContract = contract.findByUser(user);
                        if(userContract == null) continue;

                        BudgetDocument budgetDocument = createBudgetDocument(user, startDate, contract, userContract, clientList);
                        //if(user.getUsername().equals("hans.lassen")) System.out.println("budgetDocument = " + budgetDocument);
                        if (budgetDocument == null) {
                            continue;
                        }
                        if(budgetDocument.getBudgetHours()==0) continue;
                        budgetDocumentList.add(budgetDocument);
                    } else {
                        //if(user.getUsername().equals("hans.lassen")) System.out.println("AMOUNT");
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
                        //if(user.getUsername().equals("hans.lassen")) System.out.println("budgetDocument = " + budgetDocument);
                        budgetDocumentList.add(budgetDocument);
                    }
                }
            }
            BudgetDocument.persist(adjustForAvailability(budgetDocumentList, userList, startDate));
            QuarkusTransaction.commit();
        //});
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
        BudgetDocument result = null;
        double budget = userContract.getHours(); // (f.eks. 35 timer)
        if (budget != 0.0) {
            AvailabilityDocument availability = availabilityService.getConsultantAvailabilityByMonth(user.getUuid(), startDate);
            double monthBudget = budget * availability.getWeeks(); // f.eks. 2019-12-01, 18 days / 5 = 3,6 weeks * 35 (budget) = 126 hours
            result = new BudgetDocument(startDate, getClient(clientList, contract), user, contract, monthBudget, monthBudget, userContract.getRate());
        }

        return result;
    }

    private Client getClient(List<Client> clientList, Contract contract) {
        return clientList.stream().filter(client -> client.getUuid().equals(contract.getClientuuid())).findAny().orElseThrow(() -> new RuntimeException(contract.toString()));
    }

}
