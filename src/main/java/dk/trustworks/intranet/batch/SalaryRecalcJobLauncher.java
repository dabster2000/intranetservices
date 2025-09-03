package dk.trustworks.intranet.batch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.batch.operations.JobOperator;
import java.time.LocalDate;
import java.util.Properties;

@ApplicationScoped
public class SalaryRecalcJobLauncher {

    private static final String JOB_NAME = "user-salary-forward-recalc";

    @Inject
    JobOperator jobOperator; // standard way to start/monitor batch jobs

    public long launch(String userUuid, LocalDate eventDate, int requestedThreads) {
        LocalDate today = LocalDate.now();
        LocalDate start = eventDate != null && eventDate.isBefore(today) ? eventDate : today;
        LocalDate end   = today.plusYears(2);

        Properties props = new Properties();
        props.setProperty("userUuid", userUuid);
        props.setProperty("start", start.toString());
        props.setProperty("end", end.toString());
        props.setProperty("requestedThreads", String.valueOf(requestedThreads));

        return jobOperator.start(JOB_NAME, props);
    }
}
