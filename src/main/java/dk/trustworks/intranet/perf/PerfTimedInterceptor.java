package dk.trustworks.intranet.perf;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.util.HashMap;
import java.util.Map;

@PerfTimed("")
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 50)
public class PerfTimedInterceptor {

    @Inject
    PerfMetrics perfMetrics;

    @AroundInvoke
    Object time(InvocationContext ctx) throws Exception {
        String phase = phaseOf(ctx);
        long start = System.nanoTime();
        try {
            Object result = ctx.proceed();
            emit(phase, start, "success", null);
            return result;
        } catch (Exception e) {
            emit(phase, start, "error", e.getClass().getSimpleName());
            throw e;
        } catch (Error e) {
            emit(phase, start, "error", e.getClass().getSimpleName());
            throw e;
        }
    }

    private String phaseOf(InvocationContext ctx) {
        PerfTimed ann = ctx.getMethod() != null ? ctx.getMethod().getAnnotation(PerfTimed.class) : null;
        if (ann != null && ann.value() != null && !ann.value().isBlank()) {
            return ann.value();
        }
        return ctx.getMethod() != null ? ctx.getMethod().getName() : "unknown";
    }

    private void emit(String phase, long startNanos, String outcome, String errorType) {
        double ms = (System.nanoTime() - startNanos) / 1_000_000.0;
        Map<String, String> durDims = Map.of("phase", phase);
        Map<String, String> countDims = Map.of("phase", phase, "outcome", outcome);
        Map<String, Object> fields = new HashMap<>();
        if (errorType != null) fields.put("errorType", errorType);
        perfMetrics.emitTimer("OperationDurationMs", ms, durDims, fields);
        perfMetrics.emitCount("OperationCount", 1, countDims, fields);
    }
}
