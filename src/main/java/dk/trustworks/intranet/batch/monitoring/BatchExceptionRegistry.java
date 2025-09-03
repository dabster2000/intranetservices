package dk.trustworks.intranet.batch.monitoring;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for capturing exceptions during batch job execution.
 * Uses a combination of ThreadLocal and execution ID mapping to ensure exceptions
 * can be retrieved even if JBeret switches threads during execution.
 * 
 * This acts as a side-channel for exception data that bypasses JBeret's 
 * abstraction layer which doesn't propagate exception objects to listeners.
 */
@ApplicationScoped
@JBossLog
public class BatchExceptionRegistry {
    
    @Inject
    BatchJobTrackingService trackingService;
    
    // Primary mechanism: ThreadLocal for same-thread exception capture
    private static final ThreadLocal<ExceptionContext> threadLocalException = new ThreadLocal<>();
    
    // Fallback mechanism: Map by execution ID for cross-thread scenarios
    private final Map<Long, ExceptionContext> executionExceptions = new ConcurrentHashMap<>();
    
    /**
     * Captures an exception for the current batch execution.
     * Stores both in ThreadLocal (for same-thread access) and execution map (for cross-thread access).
     */
    public void captureException(long executionId, Throwable exception) {
        if (exception == null) return;
        
        ExceptionContext context = new ExceptionContext(executionId, exception, Thread.currentThread().getName());
        
        // Store in both mechanisms for maximum reliability
        threadLocalException.set(context);
        executionExceptions.put(executionId, context);
        
        log.debugf("Captured exception for execution %d in thread %s: %s", 
                   executionId, Thread.currentThread().getName(), exception.getMessage());
        
        // Immediately persist to database in separate transaction
        try {
            trackingService.setTrace(executionId, exception);
        } catch (Exception e) {
            log.errorf(e, "Failed to persist exception trace for execution %d", executionId);
        }
    }
    
    /**
     * Retrieves and clears the exception for the current thread or execution ID.
     * First tries ThreadLocal, then falls back to execution map.
     */
    public ExceptionContext retrieveException(long executionId) {
        // Try ThreadLocal first (most common case)
        ExceptionContext context = threadLocalException.get();
        if (context != null && context.executionId == executionId) {
            threadLocalException.remove();
            executionExceptions.remove(executionId);
            return context;
        }
        
        // Fallback to execution map (thread switch scenario)
        context = executionExceptions.remove(executionId);
        if (context != null) {
            threadLocalException.remove(); // Clean up ThreadLocal if present
            log.debugf("Retrieved exception for execution %d from map (thread switch detected)", executionId);
        }
        
        return context;
    }
    
    /**
     * Clears all exception data for the given execution ID.
     * Called at job completion to prevent memory leaks.
     */
    public void clearException(long executionId) {
        ExceptionContext context = threadLocalException.get();
        if (context != null && context.executionId == executionId) {
            threadLocalException.remove();
        }
        executionExceptions.remove(executionId);
    }
    
    /**
     * Container for exception context including execution ID and thread info.
     */
    public static class ExceptionContext {
        public final long executionId;
        public final Throwable exception;
        public final String threadName;
        public final long timestamp;
        
        public ExceptionContext(long executionId, Throwable exception, String threadName) {
            this.executionId = executionId;
            this.exception = exception;
            this.threadName = threadName;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * Emergency cleanup method to prevent memory leaks.
     * Should be called periodically to remove old entries.
     */
    public void cleanupStaleEntries() {
        long cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000); // 24 hours
        executionExceptions.entrySet().removeIf(entry -> {
            ExceptionContext ctx = entry.getValue();
            if (ctx.timestamp < cutoff) {
                log.warnf("Removing stale exception entry for execution %d (age: %d hours)",
                         ctx.executionId, (System.currentTimeMillis() - ctx.timestamp) / (1000 * 60 * 60));
                return true;
            }
            return false;
        });
    }
}