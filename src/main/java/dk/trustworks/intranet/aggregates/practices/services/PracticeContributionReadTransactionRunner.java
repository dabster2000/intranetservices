package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Opens one fresh transaction for each coherent contribution read phase. */
@ApplicationScoped
public class PracticeContributionReadTransactionRunner {
    static final String TRANSACTION_ISOLATION_SQL = "SELECT @@tx_isolation";

    @Inject
    EntityManager em;

    LongSupplier monotonicNanos = System::nanoTime;

    public <T> T requiringNew(int timeoutSeconds, Supplier<T> work) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        long transactionStarted = monotonicNanos.getAsLong();
        long timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
        T result;
        try {
            result = executeInNewTransaction(timeoutSeconds, work);
        } catch (RuntimeException failure) {
            if (monotonicNanos.getAsLong() - transactionStarted >= timeoutNanos) {
                throw new PracticeContributionReadTransactionTimeoutException(failure);
            }
            throw failure;
        }
        if (monotonicNanos.getAsLong() - transactionStarted >= timeoutNanos) {
            throw new PracticeContributionReadTransactionTimeoutException();
        }
        return result;
    }

    <T> T executeInNewTransaction(int timeoutSeconds, Supplier<T> work) {
        return QuarkusTransaction.requiringNew().timeout(timeoutSeconds).call(() -> {
            verifyRepeatableRead(timeoutSeconds);
            return work.get();
        });
    }

    void verifyRepeatableRead(int timeoutSeconds) {
        Query query = em.createNativeQuery(TRANSACTION_ISOLATION_SQL);
        int queryTimeoutMs = Math.toIntExact(Math.min(
                CxoSqlSupport.CXO_QUERY_TIMEOUT_MS,
                TimeUnit.SECONDS.toMillis(timeoutSeconds)));
        query.setHint("jakarta.persistence.query.timeout", queryTimeoutMs);
        String isolation = String.valueOf(query.getSingleResult())
                .trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        if (!"REPEATABLE_READ".equals(isolation)) {
            throw new PracticeContributionReadIsolationException(isolation);
        }
    }
}

/** Typed marker for a fresh read transaction that failed at or after its own deadline. */
final class PracticeContributionReadTransactionTimeoutException extends RuntimeException {
    PracticeContributionReadTransactionTimeoutException() {
        super("practice contribution read transaction timed out");
    }

    PracticeContributionReadTransactionTimeoutException(RuntimeException cause) {
        super("practice contribution read transaction timed out", cause);
    }
}

/** Typed marker for a fresh read transaction that cannot prove repeatable-read semantics. */
final class PracticeContributionReadIsolationException extends RuntimeException {
    PracticeContributionReadIsolationException(String actualIsolation) {
        super("practice contribution read isolation is not repeatable-read: " + actualIsolation);
    }
}
