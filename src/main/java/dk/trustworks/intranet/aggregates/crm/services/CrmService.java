package dk.trustworks.intranet.aggregates.crm.services;

import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.aggregates.crm.model.ConsultantContract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.userservice.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@JBossLog
@ApplicationScoped
public class CrmService {

    @PersistenceContext
    EntityManager em;

    @Inject
    WorkService workService;

    @Inject
    BudgetService budgetService;

    public List<ConsultantContract> getContractsOverTime(String companyuuid) {
        log.info("getContractsOverTime");

        String sql =
                """
                    SELECT cc.useruuid AS useruuid,
                           cc.contractuuid AS contractuuid,
                           cc.activeFrom AS fromdate,
                           cc.activeTo AS todate,
                           cl.uuid AS clientuuid,
                           cl.name AS clientname,
                           u.uuid AS usr_uuid,
                           u.firstname AS firstname,
                           u.lastname AS lastname,
                           b.totalBudget,
                           w.totalActual
                    FROM contract_consultants cc
                    JOIN contracts c ON cc.contractuuid = c.uuid
                    JOIN client cl ON c.clientuuid = cl.uuid
                    JOIN consultant u ON cc.useruuid = u.uuid
                    
                    -- Pre-aggregate budget data
                    LEFT JOIN (
                        SELECT contractuuid, useruuid, SUM(budgetHours * rate) AS totalBudget
                        FROM bi_budget_per_day
                        GROUP BY contractuuid, useruuid
                    ) b ON b.contractuuid = cc.contractuuid AND b.useruuid = cc.useruuid
                    
                    -- Pre-aggregate actual data
                    LEFT JOIN (
                        SELECT contractuuid, useruuid, SUM(workduration * rate) AS totalActual
                        FROM work_full
                        GROUP BY contractuuid, useruuid
                    ) w ON w.contractuuid = cc.contractuuid AND w.useruuid = cc.useruuid
                    
                    WHERE
                        cc.rate > 0
                        AND cc.activeto >= '2021-07-01'
                        AND u.companyuuid = '%s'
                    ;
                """;

        List<Tuple> results = em.createNativeQuery(sql.formatted(companyuuid), Tuple.class).getResultList();

        List<ConsultantContract> consultantContracts = results.stream().map(tuple -> {
            String userUuid = tuple.get("useruuid", String.class);
            String contractUuid = tuple.get("contractuuid", String.class);

            // Get java.sql.Date from tuple and convert it to LocalDate
            Date fromSqlDate = tuple.get("fromdate", Date.class);
            LocalDate fromDate = fromSqlDate != null ? fromSqlDate.toLocalDate() : null;

            Date toSqlDate = tuple.get("todate", Date.class);
            LocalDate toDate = toSqlDate != null ? toSqlDate.toLocalDate() : null;

            // Construct a Client object
            Client client = new Client();
            client.setUuid(tuple.get("clientuuid", String.class));
            client.setName(tuple.get("clientname", String.class));

            // Construct a User object
            User user = new User();
            user.setUuid(tuple.get("usr_uuid", String.class));
            user.setFirstname(tuple.get("firstname", String.class));
            user.setLastname(tuple.get("lastname", String.class));

            Number totalBudget = tuple.get("totalBudget", Number.class);
            int budgetAmount = (totalBudget != null) ? totalBudget.intValue() : 0;

            Number totalActual = tuple.get("totalActual", Number.class);
            int actualAmount = (totalActual != null) ? totalActual.intValue() : 0;

            assert fromDate != null;
            assert toDate != null;
            ConsultantContract ccObj = new ConsultantContract(userUuid, contractUuid, fromDate, toDate, client, user);
            ccObj.setBudgetAmount(budgetAmount);
            ccObj.setActualAmount(actualAmount);
            return ccObj;
        }).collect(Collectors.toList());

        // If you still need to merge contracts:
        List<ConsultantContract> mergedContracts = mergeConsultantContracts(consultantContracts);

        log.info("Finished getContractsOverTime");
        return mergedContracts;
    }

    /**
     * Merges all contracts that overlap or start within one month of the previous contract,
     * but only if they have the same useruuid and contractuuid.
     *
     * @param contracts the list of ConsultantContracts to merge
     * @return a new list of merged ConsultantContracts
     */
    private List<ConsultantContract> mergeConsultantContracts(List<ConsultantContract> contracts) {
        // Group contracts by (useruuid, contractuuid)
        Map<String, List<ConsultantContract>> groupedByUserAndContract = contracts.stream()
                .collect(Collectors.groupingBy(c -> c.getUseruuid() + "_" + c.getContractuuid()));

        List<ConsultantContract> mergedList = new ArrayList<>();

        // Process each group individually
        for (Map.Entry<String, List<ConsultantContract>> entry : groupedByUserAndContract.entrySet()) {
            List<ConsultantContract> group = entry.getValue();

            // Sort by start date
            group.sort(Comparator.comparing(ConsultantContract::getFromdate));

            List<ConsultantContract> mergedForGroup = new ArrayList<>();

            for (ConsultantContract current : group) {
                if (mergedForGroup.isEmpty()) {
                    // If this is the first contract in the group, add it directly with its amounts, client, and user
                    ConsultantContract newContract = new ConsultantContract(
                            current.getUseruuid(),
                            current.getContractuuid(),
                            current.getFromdate(),
                            current.getTodate(),
                            current.getClient(),
                            current.getUser()
                    );
                    newContract.setBudgetAmount(current.getBudgetAmount());
                    newContract.setActualAmount(current.getActualAmount());
                    mergedForGroup.add(newContract);
                } else {
                    // Get the last merged contract to decide if we merge or start a new one
                    ConsultantContract lastMerged = mergedForGroup.get(mergedForGroup.size() - 1);

                    // Check if current contract overlaps or is within one month of the last merged contract
                    boolean overlaps = !current.getFromdate().isAfter(lastMerged.getTodate());
                    boolean withinOneMonth = !current.getFromdate().isAfter(lastMerged.getTodate().plusMonths(1));

                    if (overlaps || withinOneMonth) {
                        // Extend the todate if needed
                        LocalDate newEndDate = current.getTodate().isAfter(lastMerged.getTodate())
                                ? current.getTodate()
                                : lastMerged.getTodate();

                        lastMerged.setTodate(newEndDate);

                        // Aggregate the budget and actual amounts
                        lastMerged.setBudgetAmount(lastMerged.getBudgetAmount() + current.getBudgetAmount());
                        lastMerged.setActualAmount(lastMerged.getActualAmount() + current.getActualAmount());

                        // client and user remain the same since we're grouping by user and contract
                    } else {
                        // No overlap and not within one month, start a new merged contract
                        ConsultantContract newContract = new ConsultantContract(
                                current.getUseruuid(),
                                current.getContractuuid(),
                                current.getFromdate(),
                                current.getTodate(),
                                current.getClient(),
                                current.getUser()
                        );
                        newContract.setBudgetAmount(current.getBudgetAmount());
                        newContract.setActualAmount(current.getActualAmount());
                        mergedForGroup.add(newContract);
                    }
                }
            }

            // Add the merged group to the overall merged list
            mergedList.addAll(mergedForGroup);
        }

        return mergedList;
    }

}
