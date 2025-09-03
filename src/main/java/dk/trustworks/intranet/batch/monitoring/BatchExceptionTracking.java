package dk.trustworks.intranet.batch.monitoring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Interceptor binding annotation to mark classes or methods for exception tracking.
 * 
 * Apply this annotation to:
 * - Entire batchlet classes to intercept all methods
 * - Specific methods (like process()) to intercept only those methods
 * 
 * The interceptor will automatically capture and track any exceptions thrown
 * from the annotated elements.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@jakarta.interceptor.InterceptorBinding
public @interface BatchExceptionTracking {
}