package dk.trustworks.intranet.perf;

import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PerfTimedInterceptorTest {

    @PerfTimed("invoice.createDraft")
    public Object sampleSuccess() { return "ok"; }

    @PerfTimed("invoice.book")
    public Object sampleFailure() { throw new IllegalStateException("boom"); }

    @Test
    void success_emitsDurationAndSuccessCount() throws Exception {
        PerfMetrics perf = mock(PerfMetrics.class);
        PerfTimedInterceptor interceptor = new PerfTimedInterceptor();
        interceptor.perfMetrics = perf;

        Method m = getClass().getMethod("sampleSuccess");
        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.getMethod()).thenReturn(m);
        when(ctx.getTarget()).thenReturn(this);
        when(ctx.proceed()).thenReturn("ok");

        Object result = interceptor.time(ctx);

        assertEquals("ok", result);
        verify(perf).emitTimer(eq("OperationDurationMs"), anyDouble(),
                eq(Map.of("phase", "invoice.createDraft")), anyMap());
        ArgumentCaptor<Map<String, String>> dims = ArgumentCaptor.forClass(Map.class);
        verify(perf).emitCount(eq("OperationCount"), eq(1.0), dims.capture(), anyMap());
        assertEquals("success", dims.getValue().get("outcome"));
    }

    @Test
    void failure_emitsErrorCount_andRethrows() throws Exception {
        PerfMetrics perf = mock(PerfMetrics.class);
        PerfTimedInterceptor interceptor = new PerfTimedInterceptor();
        interceptor.perfMetrics = perf;

        Method m = getClass().getMethod("sampleFailure");
        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.getMethod()).thenReturn(m);
        when(ctx.getTarget()).thenReturn(this);
        when(ctx.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> interceptor.time(ctx));

        ArgumentCaptor<Map<String, String>> dims = ArgumentCaptor.forClass(Map.class);
        verify(perf).emitCount(eq("OperationCount"), eq(1.0), dims.capture(), anyMap());
        assertEquals("error", dims.getValue().get("outcome"));
    }
}
