package dk.trustworks.intranet.batch;

import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.partition.PartitionMapper;
import jakarta.batch.api.partition.PartitionPlan;
import jakarta.batch.api.partition.PartitionPlanImpl;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Properties;

/**
 * Partition mapper for bulk salary recalculation across multiple users and dates.
 *
 * <p>Creates one partition for each combination of user × date (cartesian product).
 * For example: 3 users × 365 days = 1,095 partitions.
 *
 * <p>Job parameters:
 * <ul>
 *   <li>userUuids - Comma-separated list of user UUIDs</li>
 *   <li>start - Start date (inclusive) in ISO format (yyyy-MM-dd)</li>
 *   <li>end - End date (inclusive) in ISO format (yyyy-MM-dd)</li>
 *   <li>requestedThreads - Optional thread count hint (default: min(CPU, 8, partitions))</li>
 * </ul>
 */
@Dependent
@Named("multiUserDatesPartitionMapper")
@JBossLog
public class MultiUserDatesPartitionMapper implements PartitionMapper {

    @Inject @BatchProperty(name = "userUuids")
    String userUuidsStr;

    @Inject @BatchProperty(name = "start")
    String startStr;

    @Inject @BatchProperty(name = "end")
    String endStr;

    @Inject @BatchProperty(name = "requestedThreads")
    String requestedThreadsStr;

    @Override
    public PartitionPlan mapPartitions() {
        // Parse user UUIDs
        if (userUuidsStr == null || userUuidsStr.isBlank()) {
            throw new IllegalArgumentException("userUuids parameter is required");
        }
        String[] userUuids = userUuidsStr.split(",");

        // Parse date range
        LocalDate start = LocalDate.parse(startStr);
        LocalDate end = LocalDate.parse(endStr);
        if (end.isBefore(start)) end = start;

        long dayCount = ChronoUnit.DAYS.between(start, end) + 1;
        int totalPartitions = Math.toIntExact(userUuids.length * dayCount);

        log.infof("Creating partitions for %d users × %d days = %d total partitions",
                  userUuids.length, dayCount, totalPartitions);

        // Determine thread count
        int requestedThreads = parseThreadCount(requestedThreadsStr, totalPartitions);

        // Create partition properties (one per user × date combination)
        Properties[] partitionProps = new Properties[totalPartitions];
        int idx = 0;

        for (String userUuid : userUuids) {
            String trimmedUuid = userUuid.trim();
            LocalDate currentDate = start;

            while (!currentDate.isAfter(end)) {
                Properties props = new Properties();
                props.setProperty("userUuid", trimmedUuid);
                props.setProperty("date", currentDate.toString());
                partitionProps[idx++] = props;
                currentDate = currentDate.plusDays(1);
            }
        }

        PartitionPlanImpl plan = new PartitionPlanImpl();
        plan.setPartitions(totalPartitions);
        plan.setThreads(requestedThreads);
        plan.setPartitionProperties(partitionProps);

        log.infof("Partition plan: %d partitions with %d concurrent threads",
                  totalPartitions, requestedThreads);

        return plan;
    }

    private int parseThreadCount(String requestedThreadsStr, int totalPartitions) {
        int requested = 0;
        try {
            requested = Integer.parseInt(requestedThreadsStr);
        } catch (Exception ignored) {
            // Use default
        }

        if (requested <= 0) {
            // Default: cap by CPU cores and 8 (keeps headroom), then by partition count
            int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
            requested = Math.min(Math.min(cpu, 8), totalPartitions);
        } else {
            // User specified thread count - respect it but cap at partition count
            requested = Math.min(requested, totalPartitions);
        }

        return requested;
    }
}
