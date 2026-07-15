package dk.trustworks.intranet.aggregates.practices.jobs;

import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueMaterializationService;
import org.junit.jupiter.api.Test;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PracticeRevenueRefreshBatchletTest {
    @Test void invokesCanonicalMaterializer(){
        var service=mock(PracticeRevenueMaterializationService.class);
        when(service.refresh()).thenReturn(new PracticeRevenueMaterializationService.Result(false,null,"NOT_STARTED",0,0));
        ManagedExecutor executor=mock(ManagedExecutor.class);
        when(executor.submit(any(Callable.class))).thenAnswer(invocation->{
            @SuppressWarnings("unchecked") Callable<PracticeRevenueMaterializationService.Result> work=invocation.getArgument(0);
            FutureTask<PracticeRevenueMaterializationService.Result> task=new FutureTask<>(work);
            task.run();
            return task;
        });
        var batchlet=new PracticeRevenueRefreshBatchlet(); batchlet.materializationService=service;
        batchlet.managedExecutor=executor; batchlet.jobTimeout=Duration.ofMinutes(30);
        assertEquals("COMPLETED",batchlet.process()); verify(service).refresh();
    }

    @Test void cancelsTheRefreshAtTheConfiguredBatchDeadline() throws Exception{
        var service=mock(PracticeRevenueMaterializationService.class);
        ManagedExecutor executor=mock(ManagedExecutor.class);
        @SuppressWarnings("unchecked") Future<PracticeRevenueMaterializationService.Result> future=mock(Future.class);
        doReturn(future).when(executor).submit(any(Callable.class));
        when(future.get(1_800_000L,java.util.concurrent.TimeUnit.MILLISECONDS))
                .thenThrow(new TimeoutException("deadline"));
        var batchlet=new PracticeRevenueRefreshBatchlet(); batchlet.materializationService=service;
        batchlet.managedExecutor=executor; batchlet.jobTimeout=Duration.ofMinutes(30);

        IllegalStateException failure=assertThrows(IllegalStateException.class,batchlet::process);

        assertEquals("PRACTICE_REVENUE_JOB_TIMEOUT",failure.getMessage());
        verify(future).cancel(true);
    }
}
