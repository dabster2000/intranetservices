package dk.trustworks.intranet.recruitmentservice.resources;

import org.hibernate.exception.GenericJDBCException;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reflection-driven contract tests for the post-NextSign durability hooks added
 * to {@code RecruitmentResource.sendSignature} after the 2026-05-21 Kenn Milo
 * incident — see {@code docs/incidents/2026-05-21-kenn-milo-recovery.sql} and
 * {@code docs/superpowers/plans/...}. These tests do NOT boot Quarkus; they
 * verify the static helpers behave correctly, since those helpers are what
 * makes the difference between "user sees error and retries" (duplicate
 * NextSign sends) and "user sees success and local DB catches up".
 */
class RecruitmentResourceSendSignatureDurabilityTest {

    @Test
    void localPersistenceFailedHeader_constantHasExpectedValue() throws Exception {
        java.lang.reflect.Field f = RecruitmentResource.class
                .getDeclaredField("LOCAL_PERSISTENCE_FAILED_HEADER");
        f.setAccessible(true);
        Object value = f.get(null);
        assertEquals("X-Local-Persistence-Failed", value,
                "BFF route + frontend SendSignatureDialog branch on this exact header name.");
    }

    @Test
    void isTransientJdbcFailure_recognisesAgroalAcquisitionTimeout() throws Exception {
        Method m = isTransientJdbcFailureMethod();
        // The exact exception shape we observed in the Kenn Milo trace.
        Throwable agroalLike = new GenericJDBCException(
                "Unable to acquire JDBC Connection [Sorry, acquisition timeout!]",
                new SQLException("Sorry, acquisition timeout!"));
        assertTrue((boolean) m.invoke(null, agroalLike),
                "GenericJDBCException with 'Unable to acquire JDBC Connection' must be classified transient.");
    }

    @Test
    void isTransientJdbcFailure_recognisesSqlTransientException() throws Exception {
        Method m = isTransientJdbcFailureMethod();
        assertTrue((boolean) m.invoke(null, new SQLTransientException("transient")));
        assertTrue((boolean) m.invoke(null, new SQLNonTransientConnectionException("conn down")));
        assertTrue((boolean) m.invoke(null, new JDBCConnectionException(
                "lost", new SQLException("network gone"))));
    }

    @Test
    void isTransientJdbcFailure_rejectsConstraintViolations() throws Exception {
        Method m = isTransientJdbcFailureMethod();
        // SQLException without the transient marker — e.g. a duplicate key
        // violation — must NOT be classified transient. Retrying would double-
        // insert and at best produce a uk_revision_dossier_version conflict.
        assertEquals(false, m.invoke(null, new SQLException("Duplicate entry '...' for key 'PRIMARY'")));
        assertEquals(false, m.invoke(null, new RuntimeException("NPE")));
        assertEquals(false, m.invoke(null, new IllegalArgumentException("validation failed")));
    }

    @Test
    void withJdbcRetry_succeedsOnFirstAttempt_noRetryNoSleep() throws Exception {
        Method m = withJdbcRetryMethod();
        AtomicInteger attempts = new AtomicInteger();
        Callable<String> action = () -> {
            attempts.incrementAndGet();
            return "ok";
        };
        long start = System.nanoTime();
        Object result = m.invoke(null, "test", action);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertEquals("ok", result);
        assertEquals(1, attempts.get());
        assertTrue(elapsedMs < 500,
                "Successful first attempt must not sleep (took " + elapsedMs + "ms)");
    }

    @Test
    void withJdbcRetry_retriesOnceThenSucceeds() throws Exception {
        Method m = withJdbcRetryMethod();
        AtomicInteger attempts = new AtomicInteger();
        Callable<String> action = () -> {
            int n = attempts.incrementAndGet();
            if (n == 1) {
                throw new GenericJDBCException(
                        "Unable to acquire JDBC Connection [Sorry, acquisition timeout!]",
                        new SQLException("transient"));
            }
            return "recovered";
        };
        Object result = m.invoke(null, "test", action);
        assertEquals("recovered", result);
        assertEquals(2, attempts.get(), "Must retry exactly once before succeeding.");
    }

    @Test
    void withJdbcRetry_rethrowsImmediatelyOnNonTransientFailure() throws Exception {
        Method m = withJdbcRetryMethod();
        AtomicInteger attempts = new AtomicInteger();
        IllegalArgumentException boom = new IllegalArgumentException("bad input");
        Callable<String> action = () -> {
            attempts.incrementAndGet();
            throw boom;
        };
        java.lang.reflect.InvocationTargetException ite = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> m.invoke(null, "test", action));
        assertSame(boom, ite.getCause(),
                "Non-transient exceptions must be re-thrown verbatim, not wrapped.");
        assertEquals(1, attempts.get(), "Must NOT retry non-transient failures.");
    }

    @Test
    void withJdbcRetry_exhaustsRetriesAndThrowsLastException() throws Exception {
        Method m = withJdbcRetryMethod();
        AtomicInteger attempts = new AtomicInteger();
        Callable<String> action = () -> {
            attempts.incrementAndGet();
            throw new GenericJDBCException(
                    "Unable to acquire JDBC Connection [Sorry, acquisition timeout!]",
                    new SQLException("still down"));
        };
        java.lang.reflect.InvocationTargetException ite = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> m.invoke(null, "test", action));
        assertTrue(ite.getCause() instanceof GenericJDBCException,
                "Final throw must be the last transient exception, not a wrapper.");
        // JDBC_RETRY_BACKOFF_MS has 3 entries → 4 attempts total (initial + 3 retries).
        assertEquals(4, attempts.get());
    }

    // --- helpers -----------------------------------------------------------

    private static Method isTransientJdbcFailureMethod() throws NoSuchMethodException {
        Method m = RecruitmentResource.class.getDeclaredMethod(
                "isTransientJdbcFailure", Throwable.class);
        m.setAccessible(true);
        return m;
    }

    private static Method withJdbcRetryMethod() throws NoSuchMethodException {
        Method m = RecruitmentResource.class.getDeclaredMethod(
                "withJdbcRetry", String.class, Callable.class);
        m.setAccessible(true);
        return m;
    }

    @Test
    void withJdbcRetry_isStatic_andCallableContract() throws Exception {
        Method m = withJdbcRetryMethod();
        assertTrue(java.lang.reflect.Modifier.isStatic(m.getModifiers()),
                "withJdbcRetry must be static so it works in async ManagedExecutor tasks.");
        assertNotNull(m, "withJdbcRetry contract must remain stable.");
    }
}
