package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.DeliveryBounds;
import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.DeliveryPollGateway;
import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.DeliveryWatermark;
import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.SettleOutcome;
import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueDirtyMarker.DeliveryPollResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real-MariaDB proof for the two-transaction, commit-order-safe delivery-evidence cursor protocol. It
 * drives the exact {@link DeliveryEvidencePoll} SQL constants and phase functions over raw JDBC
 * connections and threads so InnoDB locking — not a mock — decides the outcome. Each poll runs its
 * settle phase (TX1) and advance/watermark phase (TX2) on separate connections that commit between
 * them, mirroring production's two {@code REQUIRES_NEW} transactions.
 *
 * <p>Opt-in and disposable, gated exactly like {@code PracticeContributionReadCoherenceMariaDbIT}. It
 * runs against the centrally migrated schema (all migrations through V413) and only ensures the live
 * delivery-chain join tables exist additively (see {@link #ensureLiveDeliveryChainTables()}). Each
 * test resets only the {@code DELIVERY_EVIDENCE} watermark singleton and deletes the
 * {@code fact_change_log} rows it inserted.
 *
 * <p>What it proves:
 * <ul>
 *   <li>A3 — the settle scan is bounded and the heavy bounds join holds no lock; a lower id that
 *       commits after a higher one is never skipped (the settle scan blocks on it);</li>
 *   <li>the non-locking {@code MAX(id)} defect is reproduced for contrast;</li>
 *   <li>two concurrent polls advance the cursor at most once; relevant vs irrelevant version bumps;</li>
 *   <li>A6 — no deadlock between the two-phase poll and a trigger-style writer;</li>
 *   <li>A2 — the reordered final scan (log-before-watermark) does not deadlock against a trigger
 *       writer, with a negative control showing the old watermark-first order does;</li>
 *   <li>retention-gap fail-closed and steady-state advancement remain intact.</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "PRACTICES_COHERENCE_JDBC_URL", matches = ".+")
class PracticeDeliveryEvidencePollMariaDbIT {

    private static final String URL = System.getenv("PRACTICES_COHERENCE_JDBC_URL");
    private static final String USER = System.getenv().getOrDefault("PRACTICES_COHERENCE_JDBC_USER", "");
    private static final String PASSWORD = System.getenv().getOrDefault("PRACTICES_COHERENCE_JDBC_PASSWORD", "");

    private static final String TEST_USER = "it-delivery-poll-user";
    private final List<BigInteger> insertedLogIds = new ArrayList<>();

    private BigInteger baseline;

    /**
     * The practices migrations create {@code fact_change_log}, the watermark and the publication, but
     * not (fully) the live delivery-chain tables that {@link DeliveryEvidencePoll#POLL_BOUNDS_SQL}
     * LEFT JOINs ({@code work}, {@code task}, {@code project}, {@code contract_project},
     * {@code contract_consultants}). In the disposable practices schema some are absent and some exist
     * only as thin stubs (e.g. {@code task} has just {@code uuid}, without {@code projectuuid}).
     *
     * <p>Setup is therefore additive and idempotent: {@code CREATE TABLE IF NOT EXISTS} for the fully
     * absent ones, then {@code ADD COLUMN IF NOT EXISTS} for exactly the nullable columns the bounds
     * query reads. It never drops, renames, or retypes anything, so on a fuller/real schema every
     * statement is a no-op and the real tables win; the added columns are invisible to any other IT
     * that does not name them, keeping this schema re-runnable.
     */
    @BeforeAll
    static void ensureLiveDeliveryChainTables() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS work (uuid VARCHAR(36) PRIMARY KEY) ENGINE=InnoDB");
            statement.execute("CREATE TABLE IF NOT EXISTS task (uuid VARCHAR(36) PRIMARY KEY) ENGINE=InnoDB");
            statement.execute("CREATE TABLE IF NOT EXISTS project (uuid VARCHAR(36) PRIMARY KEY) ENGINE=InnoDB");
            statement.execute("CREATE TABLE IF NOT EXISTS contract_project "
                    + "(uuid VARCHAR(36) PRIMARY KEY) ENGINE=InnoDB");
            statement.execute("CREATE TABLE IF NOT EXISTS contract_consultants "
                    + "(uuid VARCHAR(36) PRIMARY KEY) ENGINE=InnoDB");
            // Additive columns for exactly what POLL_BOUNDS_SQL's live joins read; no-op where present.
            statement.execute("ALTER TABLE work ADD COLUMN IF NOT EXISTS taskuuid VARCHAR(36) NULL");
            statement.execute("ALTER TABLE task ADD COLUMN IF NOT EXISTS projectuuid VARCHAR(36) NULL");
            statement.execute("ALTER TABLE contract_project ADD COLUMN IF NOT EXISTS projectuuid VARCHAR(36) NULL");
            statement.execute("ALTER TABLE contract_project ADD COLUMN IF NOT EXISTS contractuuid VARCHAR(36) NULL");
            statement.execute("ALTER TABLE contract_consultants ADD COLUMN IF NOT EXISTS contractuuid VARCHAR(36) NULL");
            statement.execute("ALTER TABLE contract_consultants ADD COLUMN IF NOT EXISTS useruuid VARCHAR(36) NULL");
            connection.commit();
        }
    }

    @BeforeEach
    void resetWatermarkToReady() throws SQLException {
        try (Connection connection = connection()) {
            baseline = currentMaxLogId(connection);
            resetWatermark(connection, baseline, BigInteger.ZERO, "READY", BigInteger.ZERO);
            connection.commit();
        }
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection connection = connection()) {
            deleteInsertedRows(connection);
            resetWatermark(connection, currentMaxLogId(connection), BigInteger.ZERO, "READY", BigInteger.ZERO);
            connection.commit();
        }
        insertedLogIds.clear();
    }

    // ---- No lower id is ever skipped; the heavy join holds no lock (A3) ----------------------------

    @Test
    void nonLockingMaxHorizonSkipsALowerIdThatCommitsLast_defectReproduction() throws Exception {
        // A allocates the lower id and holds it uncommitted; B allocates the higher id and commits.
        try (Connection a = connection(); Connection b = connection()) {
            BigInteger low = insertLogRow(a);          // uncommitted, lower id
            BigInteger high = insertLogRow(b);         // higher id
            b.commit();
            assertTrue(low.compareTo(high) < 0, "A must hold the lower id");

            try (Connection reader = connection()) {
                BigInteger observed = maxLogId(reader);
                reader.commit();
                assertEquals(high, observed,
                        "Non-locking MAX(id) returns the higher committed id while the lower is in flight");
                assertTrue(observed.compareTo(low) > 0,
                        "Advancing straight to MAX(id) would permanently skip the lower in-flight id");
            }
            a.commit();
        }
    }

    @Test
    void boundedSettleScanBlocksOnTheLowerInFlightIdAndNeverSkipsIt() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection a = connection()) {
            BigInteger low = insertLogRow(a);          // uncommitted, lower id
            BigInteger high;
            try (Connection b = connection()) {
                high = insertLogRow(b);                // higher committed id -> bounds the settle scan
                b.commit();
            }
            assertTrue(low.compareTo(high) < 0);

            // TX1's bounded settle scan (id>cursor AND id<=high) must block on the lower uncommitted id.
            Future<List<BigInteger>> settled = executor.submit(() -> {
                try (Connection poller = connection()) {
                    List<BigInteger> ids = settleScan(poller, baseline, high);
                    poller.commit();
                    return ids;
                }
            });
            assertFalse(awaitDone(settled, 750),
                    "The bounded settle scan must block while the lower id is uncommitted");

            a.commit();                                // release the lower id
            List<BigInteger> ids = settled.get(5, TimeUnit.SECONDS);
            assertTrue(ids.contains(low), "The settled range must include the lower id once it commits");
            assertTrue(ids.contains(high), "The settled range must include the higher id");
            assertEquals(high, ids.getLast(), "settledTarget is the true high-water, with nothing skipped");
        } finally {
            executor.shutdownNow();
        }
    }

    // ---- A3: no log lock is held across the heavy bounds join --------------------------------------

    @Test
    void pollHoldsNoLogLockDuringTheBoundsJoinSoInsertsAreNotBlocked() throws Exception {
        try (Connection seed = connection()) {
            insertLogRow(seed);                        // something committed for the settle to bound
            seed.commit();
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // TX1 settle: bounded lock, then COMMIT — releases every log lock before any join.
            SettleOutcome outcome;
            try (Connection tx1 = connection()) {
                outcome = DeliveryEvidencePoll.settle(new JdbcPollGateway(tx1));
                tx1.commit();
            }
            assertEquals(SettleOutcome.Kind.SETTLED, outcome.kind());

            // TX2 runs the (potentially multi-second) bounds join holding NO log lock. A concurrent
            // fact_change_log INSERT — i.e. a work registration — must not be blocked by it.
            try (Connection tx2 = connection()) {
                new JdbcPollGateway(tx2).pollBounds(outcome.cursor(), outcome.settledTarget());
                Future<BigInteger> inserted = executor.submit(() -> {
                    try (Connection w = connection()) {
                        BigInteger id = insertLogRow(w);
                        w.commit();
                        return id;
                    }
                });
                assertTrue(awaitDone(inserted, 2000),
                        "A3: the bounds join must hold no log lock, so concurrent INSERTs are not blocked");
                inserted.get(5, TimeUnit.SECONDS);
                tx2.commit();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // ---- A6: the two-phase poll never deadlocks against a trigger-style writer ----------------------

    @Test
    void twoPhasePollDoesNotDeadlockAgainstAConcurrentTriggerStyleWriter() throws Exception {
        BigInteger seeded;
        try (Connection seed = connection()) {
            seeded = insertLogRow(seed);               // one committed row for TX2 to advance over
            seed.commit();
        }
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch pollDone = new CountDownLatch(1);
        try {
            // Writer mirrors the V412 triggers: INSERT fact_change_log then UPDATE the watermark row.
            Future<Boolean> writer = executor.submit(() -> {
                try (Connection w = connection()) {
                    insertLogRow(w);                   // holds a fresh (uncommitted) log row
                    touchWatermark(w);                 // then contends the watermark with TX2
                    w.commit();
                    return false;
                } catch (SQLException e) {
                    return isDeadlockOrLockWait(e);
                }
            });

            Future<DeliveryPollResult> poll = executor.submit(() -> {
                try {
                    return runPoll();
                } finally {
                    pollDone.countDown();
                }
            });

            DeliveryPollResult result = poll.get(20, TimeUnit.SECONDS);
            boolean writerDeadlocked = writer.get(20, TimeUnit.SECONDS);
            assertFalse(writerDeadlocked, "The trigger-style writer must not deadlock");
            assertFalse(result.deferred(), "The poll should complete (advance or no-op), never deadlock");
            assertTrue(result.observedCursor().compareTo(seeded) >= 0);
        } finally {
            executor.shutdownNow();
        }
    }

    // ---- A2: the reordered final scan is log-before-watermark, matching the writers ----------------

    @Test
    void reorderedFinalScanLogBeforeWatermarkDoesNotDeadlockAgainstTriggerWriter() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch finalScanReady = new CountDownLatch(1);
        CountDownLatch writerHoldsLog = new CountDownLatch(1);
        try {
            // Mirrors scanFinalDeliveryUnion: cursor snapshot -> log range FOR UPDATE -> watermark FOR UPDATE.
            Future<Boolean> finalScan = executor.submit(() -> {
                try (Connection f = connection()) {
                    shortLockTimeout(f);
                    BigInteger cursor = readWatermark(f).cursor();   // non-locking snapshot
                    finalScanReady.countDown();
                    assertTrue(writerHoldsLog.await(5, TimeUnit.SECONDS));
                    lockingHorizon(f, cursor);                       // log range first
                    lockWatermarkRow(f);                             // then the watermark
                    f.commit();
                    return false;
                } catch (SQLException e) {
                    return isDeadlockOrLockWait(e);
                }
            });

            Future<Boolean> writer = executor.submit(() -> {
                try (Connection w = connection()) {
                    shortLockTimeout(w);
                    assertTrue(finalScanReady.await(5, TimeUnit.SECONDS));
                    insertLogRow(w);                                 // holds the log row
                    writerHoldsLog.countDown();
                    Thread.sleep(300);                               // let the final scan block on the log
                    touchWatermark(w);                               // final scan does not hold it yet
                    w.commit();
                    return false;
                } catch (SQLException e) {
                    return isDeadlockOrLockWait(e);
                }
            });

            boolean deadlocked = finalScan.get(20, TimeUnit.SECONDS) || writer.get(20, TimeUnit.SECONDS);
            assertFalse(deadlocked, "log-before-watermark final scan must not deadlock against the writer");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invertedWatermarkFirstOrderDeadlocksAgainstTheTriggerWriter() throws Exception {
        // Negative control: the pre-A2 order (watermark FOR UPDATE, then the log FOR UPDATE) inverts
        // against the trigger writer (log, then watermark) and must deadlock.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch invertedHoldsWatermark = new CountDownLatch(1);
        CountDownLatch writerHoldsLog = new CountDownLatch(1);
        try {
            Future<Boolean> invertedScan = executor.submit(() -> {
                try (Connection p = connection()) {
                    shortLockTimeout(p);
                    lockWatermarkRow(p);               // watermark first (the inverted order)
                    invertedHoldsWatermark.countDown();
                    assertTrue(writerHoldsLog.await(5, TimeUnit.SECONDS));
                    lockingHorizon(p, baseline);       // then the log tail -> completes the cycle
                    p.commit();
                    return false;
                } catch (SQLException e) {
                    return isDeadlockOrLockWait(e);
                }
            });

            Future<Boolean> writer = executor.submit(() -> {
                try (Connection w = connection()) {
                    shortLockTimeout(w);
                    assertTrue(invertedHoldsWatermark.await(5, TimeUnit.SECONDS));
                    insertLogRow(w);                   // holds the change-log row
                    writerHoldsLog.countDown();
                    touchWatermark(w);                 // wants the watermark -> blocked by inverted scan
                    w.commit();
                    return false;
                } catch (SQLException e) {
                    return isDeadlockOrLockWait(e);
                }
            });

            boolean deadlocked = invertedScan.get(20, TimeUnit.SECONDS) || writer.get(20, TimeUnit.SECONDS);
            assertTrue(deadlocked,
                    "The watermark-first order must deadlock (or lock-wait timeout) against the writer");
        } finally {
            executor.shutdownNow();
        }
    }

    // ---- Two concurrent polls advance at most once -------------------------------------------------

    @Test
    void twoConcurrentPollsAdvanceTheCursorAtMostOnce() throws Exception {
        BigInteger max;
        try (Connection seed = connection()) {
            insertLogRow(seed);
            insertLogRow(seed);
            max = insertLogRow(seed);
            seed.commit();
        }
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<DeliveryPollResult>> polls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                polls.add(executor.submit(() -> {
                    go.await(5, TimeUnit.SECONDS);
                    return runPoll();
                }));
            }
            go.countDown();

            int advanced = 0;
            for (Future<DeliveryPollResult> poll : polls) {
                DeliveryPollResult result = pollResult(poll);
                if (result != null && result.observedCursor().compareTo(result.previousCursor()) > 0) {
                    advanced++;
                }
            }
            assertEquals(1, advanced, "Exactly one of the two concurrent polls advances the cursor");
            try (Connection check = connection()) {
                assertEquals(max, readWatermark(check).cursor(),
                        "The cursor settles at the true high-water exactly once");
                check.commit();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // ---- Relevant vs irrelevant advance ------------------------------------------------------------

    @Test
    void relevantAdvanceBumpsVersionOnceAndIrrelevantAdvanceDoesNot() throws Exception {
        try (Connection connection = connection()) {
            BigInteger version = sourceVersion(connection);
            BigInteger cursor = baseline;

            BigInteger relevantTarget = cursor.add(BigInteger.ONE);
            assertTrue(runAdvance(connection, cursor, relevantTarget, LocalDate.of(2026, 6, 1),
                    LocalDate.of(2026, 6, 1), true));
            assertEquals(version.add(BigInteger.ONE), sourceVersion(connection));
            assertEquals(relevantTarget, readWatermark(connection).cursor());

            BigInteger irrelevantTarget = relevantTarget.add(BigInteger.ONE);
            assertTrue(runAdvance(connection, relevantTarget, irrelevantTarget, null, null, false));
            assertEquals(version.add(BigInteger.ONE), sourceVersion(connection),
                    "An irrelevant batch must not bump the source version");
            assertEquals(irrelevantTarget, readWatermark(connection).cursor());
            connection.commit();
        }
    }

    // ---- Retention-gap fail-closed and steady-state resume -----------------------------------------

    @Test
    void retentionGapFailsClosedAndSteadyStateAdvancementResumes() throws Exception {
        try (Connection setup = connection()) {
            resetWatermark(setup, baseline, baseline.add(BigInteger.valueOf(5)), "READY", BigInteger.ZERO);
            setup.commit();
        }

        DeliveryPollResult failing = runPoll();
        assertTrue(failing.relevant(), "A retention gap fails closed and reports the transition");
        try (Connection check = connection()) {
            DeliveryWatermark failed = readWatermark(check);
            check.commit();
            assertEquals("FAILED", failed.state());
            assertEquals("FACT_CHANGE_LOG_RETENTION_GAP", failed.retentionGapReason());
        }

        BigInteger optimisticAfterFail;
        try (Connection check = connection()) {
            optimisticAfterFail = optimisticVersion(check);
            check.commit();
        }
        DeliveryPollResult again = runPoll();
        assertTrue(again.deferred(), "An already-failed retention gap defers without repeating the write");
        try (Connection check = connection()) {
            assertEquals(optimisticAfterFail, optimisticVersion(check), "No repeated retention-gap mutation");
            check.commit();
        }

        // Steady state resumes after recovery restores a READY cursor.
        BigInteger newId;
        try (Connection setup = connection()) {
            resetWatermark(setup, baseline, BigInteger.ZERO, "READY", BigInteger.ZERO);
            newId = insertLogRow(setup);
            setup.commit();
        }
        DeliveryPollResult steady = runPoll();
        assertFalse(steady.deferred());
        try (Connection check = connection()) {
            assertEquals(newId, readWatermark(check).cursor(),
                    "Steady-state advancement reaches the new high-water");
            check.commit();
        }
    }

    // ================================================================================================
    // Two-phase poll driver: TX1 settle (commit) then TX2 advance / resolve (commit), on separate
    // connections — the raw-JDBC mirror of DeliveryEvidencePoll.poll's REQUIRES_NEW phases.
    // ================================================================================================

    private DeliveryPollResult runPoll() throws SQLException {
        SettleOutcome outcome;
        try (Connection settleTx = connection()) {
            outcome = DeliveryEvidencePoll.settle(new JdbcPollGateway(settleTx));
            settleTx.commit();
        }
        switch (outcome.kind()) {
            case DEFERRED:
                return DeliveryPollResult.deferred(outcome.cursor());
            case WATERMARK_ONLY:
                try (Connection advanceTx = connection()) {
                    DeliveryPollResult result =
                            DeliveryEvidencePoll.resolveWatermarkOnly(new JdbcPollGateway(advanceTx));
                    advanceTx.commit();
                    return result;
                }
            case SETTLED:
            default:
                if (outcome.settledTarget().compareTo(outcome.cursor()) <= 0) {
                    return new DeliveryPollResult(false, false, outcome.cursor(), outcome.cursor(), null, null);
                }
                try (Connection advanceTx = connection()) {
                    DeliveryPollResult result = DeliveryEvidencePoll.advance(
                            new JdbcPollGateway(advanceTx), outcome.cursor(), outcome.settledTarget());
                    advanceTx.commit();
                    return result;
                }
        }
    }

    // ================================================================================================
    // Raw-JDBC gateway that runs the real DeliveryEvidencePoll SQL constants (named -> positional).
    // ================================================================================================

    private static final class JdbcPollGateway implements DeliveryPollGateway {
        private final Connection connection;

        private JdbcPollGateway(Connection connection) {
            this.connection = connection;
        }

        @Override
        public DeliveryWatermark snapshot() {
            return watermark(DeliveryEvidencePoll.POLL_WATERMARK_SNAPSHOT_SQL);
        }

        @Override
        public String lockPublicationStatus() {
            try (PreparedStatement ps = prepare(connection, DeliveryEvidencePoll.PUBLICATION_LOCK_SQL, Map.of());
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("PRACTICE_CONTRIBUTION publication missing");
                }
                return rs.getString(1);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public BigInteger maxLogId() {
            try (PreparedStatement ps = prepare(connection, DeliveryEvidencePoll.LOG_MAX_SQL, Map.of());
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return BigInteger.valueOf(rs.getLong(1));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public BigInteger settleHorizon(BigInteger cursor, BigInteger max) {
            List<BigInteger> ids = settleScan(connection, cursor, max);
            return ids.isEmpty() ? cursor : ids.getLast();
        }

        @Override
        public DeliveryWatermark lockWatermark() {
            return watermark(DeliveryEvidencePoll.WATERMARK_LOCK_SQL);
        }

        @Override
        public boolean failRetentionGap(BigInteger cursor, BigInteger pruned) {
            try (PreparedStatement ps = prepare(connection, DeliveryEvidencePoll.RETENTION_FAIL_SQL,
                    Map.of("cursor", cursor, "pruned", pruned))) {
                return ps.executeUpdate() == 1;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public DeliveryBounds pollBounds(BigInteger cursor, BigInteger target) {
            try (PreparedStatement ps = prepare(connection, DeliveryEvidencePoll.POLL_BOUNDS_SQL,
                    Map.of("cursor", cursor, "target", target));
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new DeliveryBounds(null, null);
                }
                return new DeliveryBounds(localDate(rs.getDate(1)), localDate(rs.getDate(2)));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean advance(BigInteger cursor, BigInteger target, LocalDate affectedStart,
                               LocalDate affectedEnd, boolean relevant) {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("target", target);
            params.put("cursor", cursor);
            params.put("relevant", relevant ? 1 : 0);
            params.put("affectedStart", affectedStart);
            params.put("affectedEnd", affectedEnd);
            try (PreparedStatement ps = prepare(connection, DeliveryEvidencePoll.ADVANCE_SQL, params)) {
                return ps.executeUpdate() == 1;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        private DeliveryWatermark watermark(String sql) {
            try (PreparedStatement ps = prepare(connection, sql, Map.of());
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("DELIVERY_EVIDENCE watermark missing");
                }
                return new DeliveryWatermark(BigInteger.valueOf(rs.getLong(1)),
                        BigInteger.valueOf(rs.getLong(2)), rs.getString(3),
                        rs.getString(4) != null, rs.getString(5));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ================================================================================================
    // JDBC helpers
    // ================================================================================================

    private static final Pattern NAMED_PARAM = Pattern.compile(":([A-Za-z]+)");

    /** Runs a named-parameter statement over raw JDBC so the IT can execute the shared SQL constants. */
    private static PreparedStatement prepare(Connection connection, String sql, Map<String, Object> params)
            throws SQLException {
        Matcher matcher = NAMED_PARAM.matcher(sql);
        List<Object> ordered = new ArrayList<>();
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!params.containsKey(name)) {
                throw new IllegalArgumentException("Missing bind for :" + name);
            }
            ordered.add(params.get(name));
            matcher.appendReplacement(rewritten, "?");
        }
        matcher.appendTail(rewritten);
        PreparedStatement ps = connection.prepareStatement(rewritten.toString());
        for (int i = 0; i < ordered.size(); i++) {
            bind(ps, i + 1, ordered.get(i));
        }
        return ps;
    }

    private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DATE);
        } else if (value instanceof BigInteger big) {
            ps.setLong(index, big.longValueExact());
        } else if (value instanceof Integer number) {
            ps.setInt(index, number);
        } else if (value instanceof LocalDate date) {
            ps.setDate(index, java.sql.Date.valueOf(date));
        } else {
            ps.setObject(index, value);
        }
    }

    /** Bounded settle scan ({@link DeliveryEvidencePoll#LOG_SETTLE_LOCK_SQL}); returns the locked ids. */
    private static List<BigInteger> settleScan(Connection connection, BigInteger cursor, BigInteger max) {
        try (PreparedStatement ps = prepare(connection, DeliveryEvidencePoll.LOG_SETTLE_LOCK_SQL,
                Map.of("cursor", cursor, "target", max));
             ResultSet rs = ps.executeQuery()) {
            List<BigInteger> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(BigInteger.valueOf(rs.getLong(1)));
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Open-ended log lock ({@link DeliveryEvidencePoll#LOG_HORIZON_LOCK_SQL}), used only by the final scan. */
    private List<BigInteger> lockingHorizon(Connection connection, BigInteger cursor) throws SQLException {
        try (PreparedStatement ps = prepare(connection, DeliveryEvidencePoll.LOG_HORIZON_LOCK_SQL,
                Map.of("cursor", cursor));
             ResultSet rs = ps.executeQuery()) {
            List<BigInteger> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(BigInteger.valueOf(rs.getLong(1)));
            }
            return ids;
        }
    }

    private boolean runAdvance(Connection connection, BigInteger cursor, BigInteger target,
                               LocalDate start, LocalDate end, boolean relevant) {
        return new JdbcPollGateway(connection).advance(cursor, target, start, end, relevant);
    }

    private void lockWatermarkRow(Connection connection) throws SQLException {
        try (PreparedStatement ps = prepare(connection, DeliveryEvidencePoll.WATERMARK_LOCK_SQL, Map.of());
             ResultSet rs = ps.executeQuery()) {
            rs.next();
        }
    }

    private void touchWatermark(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE practice_revenue_source_watermark SET last_observed_at=UTC_TIMESTAMP(6) "
                        + "WHERE source_name='DELIVERY_EVIDENCE'")) {
            ps.executeUpdate();
        }
    }

    private BigInteger insertLogRow(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO fact_change_log (useruuid, affected_date, change_type, source_table, source_id) "
                        + "VALUES (?, ?, 'WORK', 'work', ?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, TEST_USER);
            ps.setDate(2, java.sql.Date.valueOf(LocalDate.of(2026, 6, 1)));
            ps.setString(3, UUID.randomUUID().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                BigInteger id = BigInteger.valueOf(keys.getLong(1));
                insertedLogIds.add(id);
                return id;
            }
        }
    }

    private void deleteInsertedRows(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM fact_change_log WHERE source_table='work' AND useruuid=?")) {
            ps.setString(1, TEST_USER);
            ps.executeUpdate();
        }
    }

    private static BigInteger currentMaxLogId(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(MAX(id),0) FROM fact_change_log");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return BigInteger.valueOf(rs.getLong(1));
        }
    }

    private static BigInteger maxLogId(Connection connection) throws SQLException {
        return currentMaxLogId(connection);
    }

    private void resetWatermark(Connection connection, BigInteger cursor, BigInteger pruned,
                                String state, BigInteger version) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE practice_revenue_source_watermark SET last_fact_change_log_id=?, "
                        + "last_pruned_fact_change_log_id=?, source_state=?, source_version=?, "
                        + "attempt_token=NULL, recovery_token=NULL, retention_gap_reason=NULL, "
                        + "started_at=NULL, safe_reason=NULL WHERE source_name='DELIVERY_EVIDENCE'")) {
            ps.setLong(1, cursor.longValueExact());
            ps.setLong(2, pruned.longValueExact());
            ps.setString(3, state);
            ps.setLong(4, version.longValueExact());
            ps.executeUpdate();
        }
    }

    private DeliveryWatermark readWatermark(Connection connection) throws SQLException {
        try (PreparedStatement ps = prepare(connection, DeliveryEvidencePoll.POLL_WATERMARK_SNAPSHOT_SQL, Map.of());
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return new DeliveryWatermark(BigInteger.valueOf(rs.getLong(1)),
                    BigInteger.valueOf(rs.getLong(2)), rs.getString(3),
                    rs.getString(4) != null, rs.getString(5));
        }
    }

    private static BigInteger sourceVersion(Connection connection) throws SQLException {
        return scalar(connection, "SELECT source_version FROM practice_revenue_source_watermark "
                + "WHERE source_name='DELIVERY_EVIDENCE'");
    }

    private static BigInteger optimisticVersion(Connection connection) throws SQLException {
        return scalar(connection, "SELECT optimistic_version FROM practice_revenue_source_watermark "
                + "WHERE source_name='DELIVERY_EVIDENCE'");
    }

    private static BigInteger scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return BigInteger.valueOf(rs.getLong(1));
        }
    }

    private static void shortLockTimeout(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SET SESSION innodb_lock_wait_timeout=3")) {
            ps.execute();
        }
    }

    private static boolean isDeadlockOrLockWait(SQLException e) {
        for (SQLException current = e; current != null; current = current.getNextException()) {
            int code = current.getErrorCode();
            if (code == 1213 || code == 1205 || "40001".equals(current.getSQLState())) {
                return true;
            }
        }
        return "40001".equals(e.getSQLState());
    }

    private static boolean awaitDone(Future<?> future, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (future.isDone()) {
                return true;
            }
            Thread.sleep(25);
        }
        return future.isDone();
    }

    private static DeliveryPollResult pollResult(Future<DeliveryPollResult> future) throws InterruptedException {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PracticeRevenueDirtyMarker.WatermarkConflictException) {
                return null;   // a cursor conflict is an acceptable "lost the race" outcome
            }
            fail("Unexpected poll failure: " + e.getCause());
            return null;
        } catch (java.util.concurrent.TimeoutException e) {
            fail("Concurrent poll timed out");
            return null;
        }
    }

    private static LocalDate localDate(java.sql.Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private static Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        return connection;
    }
}
