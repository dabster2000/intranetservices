package dk.trustworks.intranet.aggregates.practices.jobs;

import dk.trustworks.intranet.aggregates.practices.services.PracticeCostBasisRefreshService;
import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeCostBasisRefreshBatchletTest {
    @Test
    void delegatesOnlyThroughExactTypedRequestIdentity() {
        var service = mock(PracticeCostBasisRefreshService.class);
        var jobContext = mock(JobContext.class);
        var expected = new PracticeCostBasisRefreshService.ExpectedRequest(
                BigInteger.valueOf(17), "a".repeat(64), "b".repeat(64));
        when(jobContext.getProperties()).thenReturn(expected.toJobProperties());
        when(service.refreshExact(expected)).thenReturn(new PracticeCostBasisRefreshService.Outcome(
                BigInteger.valueOf(17), "READY", null, "basis-1", "c".repeat(64)));
        var batchlet = new PracticeCostBasisRefreshBatchlet();
        batchlet.refreshService = service;
        batchlet.jobContext = jobContext;
        assertEquals("COMPLETED", batchlet.process());
        verify(service).refreshExact(expected);
    }

    @Test
    void missingExactIdentityFailsClosedBeforeServiceClaim() {
        var service = mock(PracticeCostBasisRefreshService.class);
        var jobContext = mock(JobContext.class);
        when(jobContext.getProperties()).thenReturn(new Properties());
        var batchlet = new PracticeCostBasisRefreshBatchlet();
        batchlet.refreshService = service;
        batchlet.jobContext = jobContext;

        assertThrows(IllegalArgumentException.class, batchlet::process);

        verify(service, never()).refreshExact(org.mockito.ArgumentMatchers.any());
    }
}
