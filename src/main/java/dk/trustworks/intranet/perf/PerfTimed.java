package dk.trustworks.intranet.perf;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Times the annotated method and emits OperationDurationMs / OperationCount. */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PerfTimed {
    /** Phase label used as the 'phase' dimension (must be a bounded value). */
    @Nonbinding String value();
}
