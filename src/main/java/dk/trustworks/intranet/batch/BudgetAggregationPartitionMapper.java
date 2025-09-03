package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import jakarta.batch.api.partition.PartitionMapper;
import jakarta.batch.api.partition.PartitionPlan;
import jakarta.batch.api.partition.PartitionPlanImpl;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@JBossLog
@Named("budgetAggregationPartitionMapper")
@Dependent
public class BudgetAggregationPartitionMapper implements PartitionMapper {

    @Inject
    JobContext jobContext;

    @Inject
    UserService userService;

    @Override
    public PartitionPlan mapPartitions() throws Exception {
        Properties jobParams = jobContext.getProperties();
        String startMonthStr = jobParams.getProperty("startMonth");
        String partitionsStr = jobParams.getProperty("partitions", "4");
        String delayMs = jobParams.getProperty("delayMs", "0");

        LocalDate startMonth = (startMonthStr == null || startMonthStr.isBlank())
                ? LocalDate.now().minusMonths(2).withDayOfMonth(1)
                : LocalDate.parse(startMonthStr).withDayOfMonth(1);
        LocalDate endMonth = LocalDate.now().plusMonths(2).withDayOfMonth(1);

        // Narrow user filtering: active working consultants as of startMonth
        List<User> workingUsers = userService.findWorkingUsersByDate(startMonth, ConsultantType.CONSULTANT, ConsultantType.STUDENT);
        List<String> userIds = new ArrayList<>();
        for (User u : workingUsers) userIds.add(u.getUuid());

        int totalUsers = userIds.size();
        int partitions = Math.max(1, Math.min(Integer.parseInt(partitionsStr), Math.max(1, totalUsers)));

        // Prepare partition properties
        Properties[] props = new Properties[partitions];
        int chunk = (int) Math.ceil(totalUsers / (double) partitions);
        for (int i = 0; i < partitions; i++) {
            int from = i * chunk;
            int to = Math.min(from + chunk, totalUsers);
            List<String> slice = from < to ? userIds.subList(from, to) : List.of();
            Properties p = new Properties();
            p.setProperty("users", String.join(",", slice));
            p.setProperty("startMonth", startMonth.toString());
            p.setProperty("endMonth", endMonth.toString());
            p.setProperty("delayMs", delayMs);
            p.setProperty("partitionId", String.valueOf(i));
            props[i] = p;
        }

        PartitionPlanImpl plan = new PartitionPlanImpl();
        plan.setPartitions(partitions);
        plan.setThreads(partitions);
        plan.setPartitionProperties(props);
        log.infof("BudgetAggregation partitions mapped: totalUsers=%d partitions=%d chunk=%d startMonth=%s endMonth=%s",
                totalUsers, partitions, chunk, startMonth, endMonth);
        return plan;
    }
}