package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.scheduler.Scheduled;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchRuntime;
import jakarta.batch.runtime.context.JobContext;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.time.LocalDate;
import java.util.Properties;

@ApplicationScoped
public class TestAvail {

    @Inject
    JobContext jobContext;

    @Scheduled(every="20m")
    void scheduleDaylightCalculation() {
        Properties jobParams = new Properties();
        jobParams.setProperty("date", DateUtils.stringIt(LocalDate.now()));
        // Start the batch job for each day in the month
        JobOperator jobOperator = BatchRuntime.getJobOperator();
        long executionId = jobOperator.start("batchlet", jobParams);
    }

}
