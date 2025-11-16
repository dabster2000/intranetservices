package dk.trustworks.intranet.batch.metadata;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

/**
 * Registry of parameter schemas for all batch jobs.
 *
 * This is a temporary hardcoded solution for MVP. In the future, this should be replaced
 * with annotations on the batchlet classes or extracted from XML/documentation.
 */
@ApplicationScoped
public class JobParameterRegistry {

    private final Map<String, List<JobParameter>> registry = Map.ofEntries(
        // Jobs with parameters
        Map.entry("bi-date-update", List.of(
            new JobParameter("startDate", JobParameter.ParameterType.DATE, true, null,
                "Start date for BI data recalculation (ISO format: yyyy-MM-dd)"),
            new JobParameter("endDate", JobParameter.ParameterType.DATE, true, null,
                "End date for BI data recalculation (ISO format: yyyy-MM-dd)"),
            new JobParameter("threads", JobParameter.ParameterType.INTEGER, false, "12",
                "Number of parallel threads to use for processing")
        )),

        Map.entry("user-salary-forward-recalc", List.of(
            new JobParameter("userUuid", JobParameter.ParameterType.STRING, true, null,
                "UUID of the user to recalculate salary for"),
            new JobParameter("start", JobParameter.ParameterType.DATE, true, null,
                "Start date for salary recalculation"),
            new JobParameter("end", JobParameter.ParameterType.DATE, true, null,
                "End date for salary recalculation"),
            new JobParameter("requestedThreads", JobParameter.ParameterType.INTEGER, false, "1",
                "Number of threads (usually 1 for single-user recalc)")
        )),

        Map.entry("user-salary-day-recalc", List.of(
            new JobParameter("userUuid", JobParameter.ParameterType.STRING, true, null,
                "UUID of the user to recalculate"),
            new JobParameter("date", JobParameter.ParameterType.DATE, true, null,
                "Specific date to recalculate for")
        )),

        Map.entry("invoice-phase1-migration-remap-items", List.of(
            new JobParameter("dryRun", JobParameter.ParameterType.BOOLEAN, false, "true",
                "If true, only validates without making changes")
        )),

        // Simple jobs with no parameters
        Map.entry("team-description", List.of()),
        Map.entry("mail-send", List.of()),
        Map.entry("bulk-mail-send", List.of()),
        Map.entry("economics-upload-retry", List.of()),
        Map.entry("expense-sync", List.of()),
        Map.entry("expense-create-batch", List.of()),
        Map.entry("cache-warmup", List.of()),
        Map.entry("salesforce-opportunity-aggregation", List.of()),
        Map.entry("salesforce-opportunity-sync", List.of()),
        Map.entry("salesforce-lead-sync", List.of()),
        Map.entry("salesforce-lead-aggregation", List.of()),
        Map.entry("bi-expense-update", List.of()),
        Map.entry("bi-kpi-update", List.of()),
        Map.entry("knowledge-aggregation", List.of()),
        Map.entry("finance-dashboard-aggregation", List.of()),
        Map.entry("revenue-aggregation", List.of())
    );

    /**
     * Get parameter schema for a specific job.
     *
     * @param jobName The job identifier
     * @return List of parameter definitions, empty list if job has no parameters or is unknown
     */
    public List<JobParameter> getParameters(String jobName) {
        return registry.getOrDefault(jobName, List.of());
    }

    /**
     * Check if a job is registered in this registry.
     *
     * @param jobName The job identifier
     * @return true if the job is known (even if it has no parameters)
     */
    public boolean isKnownJob(String jobName) {
        return registry.containsKey(jobName);
    }
}
