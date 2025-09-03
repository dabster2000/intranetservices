package dk.trustworks.intranet.batch.test;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.io.FileNotFoundException;
import java.sql.SQLException;
import java.util.Random;

/**
 * Test batchlet that simulates various failure scenarios to validate exception tracking.
 * 
 * This batchlet can be triggered manually to test that exceptions are properly captured
 * and persisted to the trace_log column in the batch_job_execution_tracking table.
 * 
 * To test:
 * 1. Deploy the application
 * 2. Trigger the job manually via: jobOperator.start("test-exception-tracking", properties)
 * 3. Check the database: SELECT * FROM batch_job_execution_tracking WHERE job_name = 'test-exception-tracking'
 * 4. Verify the trace_log column contains the full stack trace
 */
@Named("testExceptionBatchlet")
@Dependent
@JBossLog
public class TestExceptionBatchlet extends MonitoredBatchlet {
    
    @Override
    protected String doProcess() throws Exception {
        String testType = System.getProperty("test.exception.type", "random");
        
        log.infof("Starting test exception batchlet with type: %s", testType);
        
        // Simulate some work before failing
        for (int i = 0; i < 5; i++) {
            log.infof("Processing step %d of 5", i + 1);
            updateProgress(i, 5);
            Thread.sleep(500);
        }
        
        // Throw different types of exceptions based on test type
        switch (testType.toLowerCase()) {
            case "nullpointer":
                throwNullPointerException();
                break;
                
            case "illegalargument":
                throwIllegalArgumentException();
                break;
                
            case "sql":
                throwSQLException();
                break;
                
            case "filenotfound":
                throwFileNotFoundException();
                break;
                
            case "nested":
                throwNestedException();
                break;
                
            case "error":
                throwError();
                break;
                
            case "random":
            default:
                throwRandomException();
                break;
                
            case "success":
                log.info("Test configured for success - no exception thrown");
                return "COMPLETED";
        }
        
        return "COMPLETED"; // Never reached
    }
    
    private void throwNullPointerException() {
        String str = null;
        str.length(); // This will throw NPE
    }
    
    private void throwIllegalArgumentException() {
        throw new IllegalArgumentException("Test illegal argument: value must be positive but was -1");
    }
    
    private void throwSQLException() throws SQLException {
        throw new SQLException("Test SQL exception: Connection refused to database server at localhost:3306", 
                             "08001", 1049);
    }
    
    private void throwFileNotFoundException() throws FileNotFoundException {
        throw new FileNotFoundException("Test file not found: /nonexistent/path/to/file.txt");
    }
    
    private void throwNestedException() {
        try {
            try {
                throwSQLException();
            } catch (SQLException e) {
                throw new RuntimeException("Database operation failed", e);
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Critical system failure", e);
        }
    }
    
    private void throwError() {
        throw new OutOfMemoryError("Test out of memory error (simulated)");
    }
    
    private void throwRandomException() throws Exception {
        Random rand = new Random();
        int type = rand.nextInt(6);
        
        switch (type) {
            case 0:
                throwNullPointerException();
                break;
            case 1:
                throwIllegalArgumentException();
                break;
            case 2:
                throwSQLException();
                break;
            case 3:
                throwFileNotFoundException();
                break;
            case 4:
                throwNestedException();
                break;
            case 5:
                throw new RuntimeException("Random runtime exception with message: " + rand.nextInt(1000));
        }
    }
    
    @Override
    protected void onFinally(long executionId, String jobName) {
        log.infof("Test exception batchlet cleanup for execution %d", executionId);
        
        // Report non-fatal issues that occurred during processing
        reportNonFatalError("Test warning: Resource cleanup took longer than expected", null);
    }
}