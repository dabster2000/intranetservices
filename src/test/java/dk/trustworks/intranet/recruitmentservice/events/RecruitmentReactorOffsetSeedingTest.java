package dk.trustworks.intranet.recruitmentservice.events;

import jakarta.persistence.PessimisticLockException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Offset-row lifecycle of the reactor chassis, driven without a database.
 * <p>
 * The regression this pins is the production deadlock of 2026-08-11
 * (1213 / 40001, reactor {@code referrer-notifications}): the sweep used to
 * open with {@code INSERT IGNORE INTO recruitment_reactor_offsets ... SELECT
 * MAX(seq) FROM recruitment_events} once per reactor, and that aggregate is
 * a locking read over the tail of the append-only stream — the one page
 * every append needs. Once the offset row exists, the hot path must not read
 * the stream head at all.
 *
 * @see RecruitmentReactor#ensureOffsetRowSeededToHead()
 */
class RecruitmentReactorOffsetSeedingTest {

    /** Deadlock exactly as the driver reports it, wrapped the way Hibernate/JPA wrap it. */
    private static RuntimeException deadlock() {
        return new PessimisticLockException(
                "JDBC exception executing SQL [INSERT IGNORE INTO recruitment_reactor_offsets ...]",
                new SQLTransactionRollbackException(
                        "(conn=111499) Deadlock found when trying to get lock; try restarting transaction",
                        "40001", 1213));
    }

    // ---- the hot path takes no aggregate lock ----------------------------

    @Test
    void existingOffsetRow_neverReadsTheStreamHead() {
        Probe probe = new Probe().withExistingRow();

        probe.ensureOffsetRowSeededToHead();

        assertEquals(0, probe.headReads, "the aggregate read over recruitment_events must not run");
        assertEquals(List.of(), probe.inserts, "nothing to seed — the row is already there");
        assertEquals(1, probe.existenceChecks, "one keyed point read is the whole hot path");
    }

    @Test
    void repeatedSweeps_stayOnTheKeyedPointRead() {
        Probe probe = new Probe().withExistingRow();

        for (int sweep = 0; sweep < 10; sweep++) {
            probe.ensureOffsetRowSeededToHead();
        }

        assertEquals(0, probe.headReads);
        assertEquals(10, probe.existenceChecks);
    }

    @Test
    void liveDelivery_withExistingRow_doesNotWriteTheOffsetsTable() {
        Probe probe = new Probe().withExistingRow();

        probe.ensureOffsetRow(4710);

        assertEquals(List.of(), probe.inserts);
    }

    // ---- seeding still happens when the row is genuinely missing ---------

    @Test
    void missingOffsetRow_seedsToTheStreamHead() {
        Probe probe = new Probe().withStreamHead(4711);

        probe.ensureOffsetRowSeededToHead();

        assertEquals(1, probe.headReads);
        assertEquals(List.of(4711L), probe.inserts, "a new reactor starts at the head — no historical replay");
    }

    @Test
    void missingOffsetRow_onAnEmptyStream_seedsToZero() {
        Probe probe = new Probe().withStreamHead(0);

        probe.ensureOffsetRowSeededToHead();

        assertEquals(List.of(0L), probe.inserts);
    }

    @Test
    void liveDelivery_seedsJustBelowTheTriggeringEvent() {
        Probe probe = new Probe();

        probe.ensureOffsetRow(4710);

        assertEquals(List.of(4710L), probe.inserts);
        assertEquals(0, probe.headReads, "the live path knows its own seed — no aggregate read either");
    }

    @Test
    void liveDelivery_clampsTheSeedAtZero() {
        Probe probe = new Probe();

        probe.ensureOffsetRow(-1); // deliverLive(seq=0) — defensive, seq starts at 1

        assertEquals(List.of(0L), probe.inserts);
    }

    // ---- bounded deadlock retry on the one insert that remains -----------

    @Test
    void deadlockWhileSeeding_isRetriedAndSucceeds() {
        Probe probe = new Probe().withStreamHead(12).failing(deadlock(), 1);

        probe.ensureOffsetRowSeededToHead();

        assertEquals(2, probe.insertAttempts, "one failure, one successful retry");
        assertEquals(List.of(12L), probe.inserts);
    }

    @Test
    void deadlockReportedOnlyByVendorCode_isRetried() {
        RuntimeException noSqlState = new IllegalStateException(
                "wrapped", new SQLException("Deadlock found when trying to get lock", null, 1213));
        Probe probe = new Probe().withStreamHead(12).failing(noSqlState, 1);

        probe.ensureOffsetRowSeededToHead();

        assertEquals(2, probe.insertAttempts);
    }

    @Test
    void relentlessDeadlock_givesUpAfterTheBoundAndPropagates() {
        Probe probe = new Probe().withStreamHead(12).failing(deadlock(), Integer.MAX_VALUE);

        assertThrows(PessimisticLockException.class, probe::ensureOffsetRowSeededToHead);

        assertEquals(RecruitmentReactor.SEED_MAX_ATTEMPTS, probe.insertAttempts, "retry must be bounded");
    }

    @Test
    void nonDeadlockFailure_isNotRetried() {
        RuntimeException boom = new IllegalStateException("connection refused");
        Probe probe = new Probe().withStreamHead(12).failing(boom, Integer.MAX_VALUE);

        RuntimeException thrown = assertThrows(IllegalStateException.class, probe::ensureOffsetRowSeededToHead);

        assertSame(boom, thrown, "the original failure must surface unchanged");
        assertEquals(1, probe.insertAttempts);
    }

    @Test
    void selfReferencingCauseChain_terminates() {
        RuntimeException loop = new RuntimeException("self-caused") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        Probe probe = new Probe().withStreamHead(12).failing(loop, Integer.MAX_VALUE);

        assertThrows(RuntimeException.class, probe::ensureOffsetRowSeededToHead);

        assertEquals(1, probe.insertAttempts);
    }

    // ---- probe -----------------------------------------------------------

    /**
     * Reactor with the three database seams stubbed out — the chassis logic
     * above them is what is under test, and it must hold without a
     * transaction manager or a connection.
     */
    private static final class Probe extends RecruitmentReactor {

        private boolean rowExists;
        private long streamHead;
        private RuntimeException failure;
        private int failuresLeft;

        int existenceChecks;
        int headReads;
        int insertAttempts;
        final List<Long> inserts = new ArrayList<>();

        Probe withExistingRow() {
            this.rowExists = true;
            return this;
        }

        Probe withStreamHead(long head) {
            this.streamHead = head;
            return this;
        }

        Probe failing(RuntimeException failure, int times) {
            this.failure = failure;
            this.failuresLeft = times;
            return this;
        }

        @Override
        public String name() {
            return "probe";
        }

        @Override
        protected void handle(RecruitmentEvent event) {
            throw new UnsupportedOperationException("not exercised by the offset-row tests");
        }

        @Override
        boolean offsetRowExists() {
            existenceChecks++;
            return rowExists;
        }

        @Override
        long currentStreamHead() {
            headReads++;
            return streamHead;
        }

        @Override
        void insertOffsetRow(long seed) {
            insertAttempts++;
            if (failuresLeft > 0) {
                failuresLeft--;
                throw failure;
            }
            inserts.add(seed);
            rowExists = true;
        }
    }
}
