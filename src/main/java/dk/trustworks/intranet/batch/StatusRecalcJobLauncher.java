package dk.trustworks.intranet.batch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.batch.operations.JobOperator;
import java.time.LocalDate;
import java.util.Properties;

@ApplicationScoped
public class StatusRecalcJobLauncher {

    private static final String JOB_NAME = "user-status-forward-recalc";

    @Inject
    JobOperator jobOperator;

    /**
     * Launch a batch job to recalculate user data from the status change date forward.
     * This ensures that status changes (e.g., ACTIVE to TERMINATED) properly affect
     * all future dates for availability, budgets, and salary calculations.
     * 
     * @param userUuid The user whose status changed
     * @param eventDate The date when the status change takes effect
     * @param requestedThreads Number of threads to use for parallel processing
     * @return The job execution ID
     */
    public long launch(String userUuid, LocalDate eventDate, int requestedThreads) {
        LocalDate today = LocalDate.now();
        // Start from the status change date or today, whichever is earlier
        LocalDate start = eventDate != null && eventDate.isBefore(today) ? eventDate : today;
        // Recalculate up to 2 years in the future
        LocalDate end = today.plusYears(2);

        Properties props = new Properties();
        props.setProperty("userUuid", userUuid);
        props.setProperty("start", start.toString());
        props.setProperty("end", end.toString());
        props.setProperty("requestedThreads", String.valueOf(requestedThreads));

        return jobOperator.start(JOB_NAME, props);
    }
}