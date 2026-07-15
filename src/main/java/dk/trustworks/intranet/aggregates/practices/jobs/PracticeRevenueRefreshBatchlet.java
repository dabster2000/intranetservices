package dk.trustworks.intranet.aggregates.practices.jobs;

import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueMaterializationService;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@JBossLog
@Dependent
@Named("practiceRevenueRefreshBatchlet")
@BatchExceptionTracking
public class PracticeRevenueRefreshBatchlet extends AbstractBatchlet {
    @Inject PracticeRevenueMaterializationService materializationService;
    @Inject ManagedExecutor managedExecutor;
    @ConfigProperty(name="practices.contribution.job-timeout", defaultValue="PT30M") Duration jobTimeout;
    final AtomicReference<Future<?>> activeRefresh=new AtomicReference<>();

    @Override @ActivateRequestContext public String process() {
        if(jobTimeout==null||jobTimeout.isZero()||jobTimeout.isNegative()
                ||jobTimeout.toMillis()<=0){
            throw new IllegalStateException("invalid practices contribution job timeout");
        }
        Future<PracticeRevenueMaterializationService.Result> future=
                managedExecutor.submit(materializationService::refresh);
        if(!activeRefresh.compareAndSet(null,future)){
            future.cancel(true);
            throw new IllegalStateException("PRACTICE_REVENUE_JOB_ALREADY_RUNNING");
        }
        PracticeRevenueMaterializationService.Result result;
        try{
            result=future.get(jobTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }catch(TimeoutException timeout){
            future.cancel(true);
            throw new IllegalStateException("PRACTICE_REVENUE_JOB_TIMEOUT",timeout);
        }catch(InterruptedException interrupted){
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PRACTICE_REVENUE_JOB_INTERRUPTED",interrupted);
        }catch(CancellationException cancelled){
            throw new IllegalStateException("PRACTICE_REVENUE_JOB_INTERRUPTED",cancelled);
        }catch(ExecutionException failure){
            Throwable cause=failure.getCause();
            if(cause instanceof RuntimeException runtime)throw runtime;
            throw new IllegalStateException("PRACTICE_REVENUE_JOB_FAILED",cause);
        }finally{
            activeRefresh.compareAndSet(future,null);
        }
        log.infof("practice revenue refresh generation=%s status=%s items=%d allocations=%d",
                result.generationId(),result.status(),result.itemCount(),result.allocationCount());
        return "COMPLETED";
    }

    @Override public void stop(){
        Future<?> future=activeRefresh.get();
        if(future!=null)future.cancel(true);
    }
}
