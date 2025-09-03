package dk.trustworks.intranet.batch;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Named;
import jakarta.inject.Inject;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.partition.PartitionMapper;
import jakarta.batch.api.partition.PartitionPlan;
import jakarta.batch.api.partition.PartitionPlanImpl;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Properties;

@Dependent
@Named("userForwardDatesPartitionMapper")
public class UserForwardDatesPartitionMapper implements PartitionMapper {

    @Inject @BatchProperty(name = "start")
    String startStr;

    @Inject @BatchProperty(name = "end")
    String endStr;

    // optional hint; if not set we compute a sane default
    @Inject @BatchProperty(name = "requestedThreads")
    String requestedThreadsStr;

    @Override
    public PartitionPlan mapPartitions() {
        LocalDate start = LocalDate.parse(startStr);
        LocalDate end   = LocalDate.parse(endStr);
        if (end.isBefore(start)) end = start;

        int partitions = Math.toIntExact(ChronoUnit.DAYS.between(start, end) + 1);

        int requested = 0;
        try { requested = Integer.parseInt(requestedThreadsStr); } catch (Exception ignored) { }
        if (requested <= 0) {
            // default: cap by CPU and 8 (keeps headroom), then by partition count
            int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
            requested = Math.min(Math.min(cpu, 8), partitions);
        } else {
            requested = Math.min(requested, partitions);
        }

        Properties[] pp = new Properties[partitions];
        LocalDate d = start;
        for (int i = 0; i < partitions; i++) {
            Properties p = new Properties();
            p.setProperty("date", d.toString());
            pp[i] = p;
            d = d.plusDays(1);
        }

        PartitionPlanImpl plan = new PartitionPlanImpl();
        plan.setPartitions(partitions);
        plan.setThreads(requested);
        plan.setPartitionProperties(pp);
        return plan;
    }
}
