package dk.trustworks.intranet.batch;

import jakarta.batch.operations.JobOperator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.Properties;

@ApplicationScoped
public class ContractConsultantRecalcJobLauncher {

    private static final String JOB_NAME = "contract-consultant-forward-recalc";

    @Inject
    JobOperator jobOperator;

    public long launch(String userUuid, LocalDate eventDate, int requestedThreads) {
        LocalDate today = LocalDate.now();
        LocalDate start = (eventDate != null && eventDate.isBefore(today)) ? eventDate : today;
        LocalDate end = today.plusYears(2);

        Properties props = new Properties();
        props.setProperty("userUuid", userUuid);
        props.setProperty("start", start.toString());
        props.setProperty("end", end.toString());
        props.setProperty("requestedThreads", String.valueOf(requestedThreads));

        return jobOperator.start(JOB_NAME, props);
    }
}
