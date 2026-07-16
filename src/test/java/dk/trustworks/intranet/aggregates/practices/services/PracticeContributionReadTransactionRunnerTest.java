package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeContributionReadTransactionRunnerTest {

    @Test
    void repeatableReadIsolationIsVerifiedInsideTheFreshTransaction() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(PracticeContributionReadTransactionRunner.TRANSACTION_ISOLATION_SQL))
                .thenReturn(query);
        when(query.getSingleResult()).thenReturn("REPEATABLE-READ");
        PracticeContributionReadTransactionRunner runner = new PracticeContributionReadTransactionRunner();
        runner.em = em;

        runner.verifyRepeatableRead(2);

        verify(query).setHint("jakarta.persistence.query.timeout", 2_000);
    }

    @Test
    void weakerIsolationFailsClosedWithTypedMarker() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(PracticeContributionReadTransactionRunner.TRANSACTION_ISOLATION_SQL))
                .thenReturn(query);
        when(query.getSingleResult()).thenReturn("READ-COMMITTED");
        PracticeContributionReadTransactionRunner runner = new PracticeContributionReadTransactionRunner();
        runner.em = em;

        PracticeContributionReadIsolationException error = assertThrows(
                PracticeContributionReadIsolationException.class,
                () -> runner.verifyRepeatableRead(20));

        assertEquals("practice contribution read isolation is not repeatable-read: READ_COMMITTED",
                error.getMessage());
        verify(query).setHint("jakarta.persistence.query.timeout", 15_000);
    }

    @Test
    void failureAtOwnTransactionDeadlineGetsTypedTimeoutMarker() {
        RuntimeException rollback = new RuntimeException("transaction rolled back");
        PracticeContributionReadTransactionRunner runner = failingRunner(rollback);
        runner.monotonicNanos = sequencedNanos(0L, 2_000_000_000L);

        PracticeContributionReadTransactionTimeoutException timeout = assertThrows(
                PracticeContributionReadTransactionTimeoutException.class,
                () -> runner.requiringNew(2, () -> "unused"));

        assertSame(rollback, timeout.getCause());
    }

    @Test
    void failureBeforeOwnTransactionDeadlineRemainsTheOriginalFailure() {
        RuntimeException businessFailure = new RuntimeException("business failure");
        PracticeContributionReadTransactionRunner runner = failingRunner(businessFailure);
        runner.monotonicNanos = sequencedNanos(0L, 1_999_999_999L);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> runner.requiringNew(2, () -> "unused"));

        assertSame(businessFailure, thrown);
    }

    @Test
    void successfulWorkCompletingAtOwnTransactionDeadlineGetsTypedTimeoutMarker() {
        PracticeContributionReadTransactionRunner runner = new PracticeContributionReadTransactionRunner() {
            @Override
            <T> T executeInNewTransaction(int timeoutSeconds, Supplier<T> work) {
                return work.get();
            }
        };
        runner.monotonicNanos = sequencedNanos(0L, 2_000_000_000L);

        assertThrows(PracticeContributionReadTransactionTimeoutException.class,
                () -> runner.requiringNew(2, () -> "late result"));
    }

    private static PracticeContributionReadTransactionRunner failingRunner(RuntimeException failure) {
        return new PracticeContributionReadTransactionRunner() {
            @Override
            <T> T executeInNewTransaction(int timeoutSeconds, Supplier<T> work) {
                throw failure;
            }
        };
    }

    private static LongSupplier sequencedNanos(long... values) {
        List<Long> remaining = new ArrayList<>();
        for (long value : values) remaining.add(value);
        return remaining::removeFirst;
    }
}
