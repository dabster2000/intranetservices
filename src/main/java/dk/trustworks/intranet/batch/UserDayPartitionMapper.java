package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.partition.PartitionMapper;
import jakarta.batch.api.partition.PartitionPlan;
import jakarta.batch.api.partition.PartitionPlanImpl;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@JBossLog
@Named("userDayPartitionMapper")
@Dependent
public class UserDayPartitionMapper implements PartitionMapper {

    @Inject UserService userService;

    @Inject @BatchProperty(name = "startDate")
    String startParam;

    @Inject @BatchProperty(name = "endDate")
    String endParam;

    @Inject @BatchProperty(name = "threads")
    String threadsParam;

    @Override
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW) // opens a tx so Panache/JPA can be used here
    public PartitionPlan mapPartitions() {
        // Properties are now injected via @BatchProperty
        LocalDate startDate = (startParam == null || startParam.isBlank())
                ? LocalDate.now().withDayOfMonth(1).minusMonths(2)
                : LocalDate.parse(startParam);

        LocalDate endDate = (endParam == null || endParam.isBlank())
                ? LocalDate.now().withDayOfMonth(1).plusMonths(24)
                : LocalDate.parse(endParam);

        // Fetch users inside the active request/transaction context
        // Only keep UUIDs; don't retain managed entities outside this method
        List<String> userIds = userService.listAll(true).stream()
                .map(User::getUuid)
                .toList();

        // Log mapper configuration for verification
        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
        int userCount = userIds.size();
        long totalPartitions = days * userCount;

        log.infof("[BI-DATE-UPDATE] Mapper configured - Received params: startDate=%s, endDate=%s, threads=%s",
                 startParam, endParam, threadsParam);
        log.infof("[BI-DATE-UPDATE] Mapper calculated - startDate=%s, endDate=%s, days=%d, users=%d, totalPartitions=%d",
                 startDate, endDate, days, userCount, totalPartitions);

        List<Properties> parts = new ArrayList<>();
        for (String uid : userIds) {
            for (LocalDate d = startDate; d.isBefore(endDate); d = d.plusDays(1)) {
                Properties p = new Properties();
                p.setProperty("userUuid", uid);
                p.setProperty("day", d.toString()); // ISO yyyy-MM-dd
                parts.add(p);
            }
        }

        int partitions = parts.size();

        int requestedThreads = safeParseInt(threadsParam, 0);
        int defaultThreads   = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 8));
        int threads = Math.max(1, Math.min(partitions, requestedThreads > 0 ? requestedThreads : defaultThreads));

        PartitionPlanImpl plan = new PartitionPlanImpl();
        plan.setPartitions(partitions);
        plan.setThreads(threads);
        plan.setPartitionProperties(parts.toArray(Properties[]::new));
        return plan;
    }


    private static int safeParseInt(String s, int def) {
        try { return (s == null || s.isBlank()) ? def : Integer.parseInt(s); }
        catch (Exception ignored) { return def; }
    }
}