package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.DeliveryBounds;
import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.DeliveryPollGateway;
import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.DeliveryPollTransactions;
import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.DeliveryWatermark;
import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.SettleOutcome;
import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueDirtyMarker.DeliveryPollResult;
import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueDirtyMarker.WatermarkConflictException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigInteger;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static java.math.BigInteger.TEN;
import static java.math.BigInteger.ZERO;
import static java.math.BigInteger.valueOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeDeliveryEvidencePollerTest {

    // ---------------------------------------------------------------------------------------------
    // TX1 settle: publication guard, then the bounded settle scan. No watermark lock, no join.
    // ---------------------------------------------------------------------------------------------

    @Test
    void settleLocksPublicationThenBoundedSettleScanInOrder() {
        DeliveryPollGateway gateway = mock(DeliveryPollGateway.class);
        when(gateway.snapshot()).thenReturn(ready(TEN, ZERO));
        when(gateway.lockPublicationStatus()).thenReturn("READY");
        when(gateway.maxLogId()).thenReturn(valueOf(14));
        when(gateway.settleHorizon(TEN, valueOf(14))).thenReturn(valueOf(14));

        SettleOutcome outcome = DeliveryEvidencePoll.settle(gateway);

        assertEquals(SettleOutcome.Kind.SETTLED, outcome.kind());
        assertEquals(TEN, outcome.cursor());
        assertEquals(valueOf(14), outcome.settledTarget());

        InOrder order = inOrder(gateway);
        order.verify(gateway).snapshot();
        order.verify(gateway).lockPublicationStatus();
        order.verify(gateway).maxLogId();
        order.verify(gateway).settleHorizon(TEN, valueOf(14));
    }

    @Test
    void settleDefersWhenPublicationRunningWithoutTouchingTheLog() {
        DeliveryPollGateway gateway = mock(DeliveryPollGateway.class);
        when(gateway.snapshot()).thenReturn(ready(TEN, ZERO));
        when(gateway.lockPublicationStatus()).thenReturn("RUNNING");

        SettleOutcome outcome = DeliveryEvidencePoll.settle(gateway);

        assertEquals(SettleOutcome.Kind.DEFERRED, outcome.kind());
        assertEquals(TEN, outcome.cursor());
        verify(gateway, never()).maxLogId();
        verify(gateway, never()).settleHorizon(any(), any());
    }

    @Test
    void settleRoutesRetentionGapToWatermarkOnlyWithoutLockingPublication() {
        DeliveryPollGateway gateway = mock(DeliveryPollGateway.class);
        when(gateway.snapshot()).thenReturn(new DeliveryWatermark(TEN, valueOf(11), "READY", false, null));

        SettleOutcome outcome = DeliveryEvidencePoll.settle(gateway);

        assertEquals(SettleOutcome.Kind.WATERMARK_ONLY, outcome.kind());
        verify(gateway, never()).lockPublicationStatus();
        verify(gateway, never()).maxLogId();
    }

    // ---------------------------------------------------------------------------------------------
    // TX2 advance: non-locking bounds BEFORE the watermark lock, then a cursor-guarded CAS.
    // ---------------------------------------------------------------------------------------------

    @Test
    void advanceComputesBoundsBeforeLockingTheWatermarkAndBumpsVersionOnce() {
        DeliveryPollGateway gateway = mock(DeliveryPollGateway.class);
        when(gateway.pollBounds(TEN, valueOf(14)))
                .thenReturn(new DeliveryBounds(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1)));
        when(gateway.lockWatermark()).thenReturn(ready(TEN, ZERO));
        when(gateway.advance(eq(TEN), eq(valueOf(14)), any(), any(), eq(true))).thenReturn(true);

        DeliveryPollResult result = DeliveryEvidencePoll.advance(gateway, TEN, valueOf(14));

        assertTrue(result.relevant());
        assertEquals(TEN, result.previousCursor());
        assertEquals(valueOf(14), result.observedCursor());

        InOrder order = inOrder(gateway);
        order.verify(gateway).pollBounds(TEN, valueOf(14));
        order.verify(gateway).lockWatermark();
        order.verify(gateway).advance(eq(TEN), eq(valueOf(14)), any(), any(), eq(true));
    }

    @Test
    void advanceIrrelevantBatchAdvancesCursorWithoutBumpingVersion() {
        DeliveryPollGateway gateway = mock(DeliveryPollGateway.class);
        when(gateway.pollBounds(TEN, valueOf(14))).thenReturn(new DeliveryBounds(null, null));
        when(gateway.lockWatermark()).thenReturn(ready(TEN, ZERO));
        when(gateway.advance(eq(TEN), eq(valueOf(14)), any(), any(), eq(false))).thenReturn(true);

        DeliveryPollResult result = DeliveryEvidencePoll.advance(gateway, TEN, valueOf(14));

        assertFalse(result.relevant());
        assertEquals(valueOf(14), result.observedCursor());
        verify(gateway).advance(TEN, valueOf(14), null, null, false);
    }

    @Test
    void advanceReturnsAlreadyConsumedWhenAConcurrentPollerMovedTheCursor() {
        DeliveryPollGateway gateway = mock(DeliveryPollGateway.class);
        when(gateway.pollBounds(TEN, valueOf(14))).thenReturn(new DeliveryBounds(null, null));
        when(gateway.lockWatermark()).thenReturn(ready(valueOf(14), ZERO)); // cursor already at 14

        DeliveryPollResult result = DeliveryEvidencePoll.advance(gateway, TEN, valueOf(14));

        assertFalse(result.relevant());
        assertEquals(valueOf(14), result.observedCursor());
        verify(gateway, never()).advance(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void advanceReturnsAlreadyConsumedWhenTheCasLosesTheRace() {
        DeliveryPollGateway gateway = mock(DeliveryPollGateway.class);
        when(gateway.pollBounds(TEN, valueOf(14)))
                .thenReturn(new DeliveryBounds(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1)));
        when(gateway.lockWatermark()).thenReturn(ready(TEN, ZERO));
        when(gateway.advance(eq(TEN), eq(valueOf(14)), any(), any(), eq(true))).thenReturn(false);

        DeliveryPollResult result = DeliveryEvidencePoll.advance(gateway, TEN, valueOf(14));

        assertFalse(result.relevant());
        assertEquals(TEN, result.observedCursor());
    }

    @Test
    void advanceFailsClosedOnARetentionGap() {
        DeliveryPollGateway gateway = mock(DeliveryPollGateway.class);
        when(gateway.pollBounds(TEN, valueOf(14))).thenReturn(new DeliveryBounds(null, null));
        when(gateway.lockWatermark()).thenReturn(new DeliveryWatermark(TEN, valueOf(11), "READY", false, null));
        when(gateway.failRetentionGap(TEN, valueOf(11))).thenReturn(true);

        DeliveryPollResult result = DeliveryEvidencePoll.advance(gateway, TEN, valueOf(14));

        assertTrue(result.relevant());
        assertEquals(TEN, result.observedCursor());
        verify(gateway).failRetentionGap(TEN, valueOf(11));
        verify(gateway, never()).advance(any(), any(), any(), any(), anyBoolean());
    }

    // ---------------------------------------------------------------------------------------------
    // TX2 resolveWatermarkOnly: retention-gap / defer for a non-advanceable snapshot.
    // ---------------------------------------------------------------------------------------------

    @Test
    void resolveWatermarkOnlyFailsClosedOnARetentionGap() {
        DeliveryPollGateway gateway = mock(DeliveryPollGateway.class);
        when(gateway.lockWatermark()).thenReturn(new DeliveryWatermark(TEN, valueOf(11), "READY", false, null));
        when(gateway.failRetentionGap(TEN, valueOf(11))).thenReturn(true);

        DeliveryPollResult result = DeliveryEvidencePoll.resolveWatermarkOnly(gateway);

        assertTrue(result.relevant());
        verify(gateway).failRetentionGap(TEN, valueOf(11));
    }

    @Test
    void resolveWatermarkOnlyDefersWhenAlreadyFailedWithoutRepeatingTheMutation() {
        DeliveryPollGateway gateway = mock(DeliveryPollGateway.class);
        when(gateway.lockWatermark())
                .thenReturn(new DeliveryWatermark(TEN, valueOf(11), "FAILED", false, "FACT_CHANGE_LOG_RETENTION_GAP"));

        DeliveryPollResult result = DeliveryEvidencePoll.resolveWatermarkOnly(gateway);

        assertTrue(result.deferred());
        verify(gateway, never()).failRetentionGap(any(), any());
    }

    @Test
    void resolveWatermarkOnlySurfacesARetentionGapConflict() {
        DeliveryPollGateway gateway = mock(DeliveryPollGateway.class);
        when(gateway.lockWatermark()).thenReturn(new DeliveryWatermark(TEN, valueOf(11), "READY", false, null));
        when(gateway.failRetentionGap(TEN, valueOf(11))).thenReturn(false);

        WatermarkConflictException failure = assertThrows(WatermarkConflictException.class,
                () -> DeliveryEvidencePoll.resolveWatermarkOnly(gateway));

        assertEquals("DELIVERY_EVIDENCE_RETENTION_GAP_CONFLICT", failure.getMessage());
    }

    // ---------------------------------------------------------------------------------------------
    // Orchestrator: TX1 (settle) then the right TX2 phase; each is its own REQUIRES_NEW transaction.
    // ---------------------------------------------------------------------------------------------

    @Test
    void pollSettlesThenAdvancesForASettledRange() {
        DeliveryPollTransactions tx = mock(DeliveryPollTransactions.class);
        when(tx.settle()).thenReturn(SettleOutcome.settled(TEN, valueOf(14)));
        DeliveryPollResult advanced = new DeliveryPollResult(false, true, TEN, valueOf(14), null, null);
        when(tx.advance(TEN, valueOf(14))).thenReturn(advanced);

        DeliveryPollResult result = DeliveryEvidencePoll.poll(tx);

        assertEquals(advanced, result);
        InOrder order = inOrder(tx);
        order.verify(tx).settle();
        order.verify(tx).advance(TEN, valueOf(14));
        verify(tx, never()).resolveWatermarkOnly();
    }

    @Test
    void pollShortCircuitsWhenNothingNewWasSettled() {
        DeliveryPollTransactions tx = mock(DeliveryPollTransactions.class);
        when(tx.settle()).thenReturn(SettleOutcome.settled(TEN, TEN));

        DeliveryPollResult result = DeliveryEvidencePoll.poll(tx);

        assertFalse(result.relevant());
        assertEquals(TEN, result.observedCursor());
        verify(tx, never()).advance(any(), any());
        verify(tx, never()).resolveWatermarkOnly();
    }

    @Test
    void pollRoutesWatermarkOnlyOutcomesToTheWatermarkPhase() {
        DeliveryPollTransactions tx = mock(DeliveryPollTransactions.class);
        when(tx.settle()).thenReturn(SettleOutcome.watermarkOnly(TEN));
        DeliveryPollResult deferred = DeliveryPollResult.deferred(TEN);
        when(tx.resolveWatermarkOnly()).thenReturn(deferred);

        DeliveryPollResult result = DeliveryEvidencePoll.poll(tx);

        assertEquals(deferred, result);
        verify(tx, never()).advance(any(), any());
    }

    @Test
    void pollDefersWhenSettleDefers() {
        DeliveryPollTransactions tx = mock(DeliveryPollTransactions.class);
        when(tx.settle()).thenReturn(SettleOutcome.deferred(TEN));

        DeliveryPollResult result = DeliveryEvidencePoll.poll(tx);

        assertTrue(result.deferred());
        assertEquals(TEN, result.observedCursor());
        verify(tx, never()).advance(any(), any());
        verify(tx, never()).resolveWatermarkOnly();
    }

    // ---------------------------------------------------------------------------------------------
    // Statement shapes: the settle scan is bounded and locking, the horizon read is a plain MAX, the
    // bounds join is NON-locking (no lock held across it), and the writes stay cursor-guarded.
    // ---------------------------------------------------------------------------------------------

    @Test
    void pollStatementsHaveTheCommitOrderSafeLowContentionShape() {
        assertFalse(DeliveryEvidencePoll.POLL_WATERMARK_SNAPSHOT_SQL.contains("FOR UPDATE"));
        assertTrue(DeliveryEvidencePoll.WATERMARK_LOCK_SQL.stripTrailing().endsWith("FOR UPDATE"));
        assertTrue(DeliveryEvidencePoll.PUBLICATION_LOCK_SQL.contains("FOR UPDATE"));

        // The horizon high-water is a cheap non-locking MAX; the settle lock is BOUNDED by it.
        assertEquals("SELECT COALESCE(MAX(id),0) FROM fact_change_log", DeliveryEvidencePoll.LOG_MAX_SQL);
        assertFalse(DeliveryEvidencePoll.LOG_MAX_SQL.contains("FOR UPDATE"));
        assertTrue(DeliveryEvidencePoll.LOG_SETTLE_LOCK_SQL
                .contains("WHERE id>:cursor AND id<=:target ORDER BY id FOR UPDATE"));
        // The open-ended horizon remains only for the attempt-owned final scan.
        assertTrue(DeliveryEvidencePoll.LOG_HORIZON_LOCK_SQL.contains("WHERE id>:cursor ORDER BY id FOR UPDATE"));

        // The heavy relevance join must NOT hold a lock (A3): it is a plain snapshot read in TX2.
        assertFalse(DeliveryEvidencePoll.POLL_BOUNDS_SQL.contains("FOR UPDATE"));
        assertTrue(DeliveryEvidencePoll.POLL_BOUNDS_SQL.contains("d.generation_id=p.published_generation_id"));
        assertFalse(DeliveryEvidencePoll.POLL_BOUNDS_SQL.contains("attempt_generation_id"));
        assertTrue(DeliveryEvidencePoll.POLL_BOUNDS_SQL.contains("JOIN work live_work"));
        assertTrue(DeliveryEvidencePoll.POLL_BOUNDS_SQL.contains("live_contract_consultant.uuid=f.source_id"));

        assertTrue(DeliveryEvidencePoll.ADVANCE_SQL.contains("last_fact_change_log_id=:cursor"));
        assertTrue(DeliveryEvidencePoll.RETENTION_FAIL_SQL.contains("last_fact_change_log_id=:cursor"));
        assertTrue(DeliveryEvidencePoll.RETENTION_FAIL_SQL.contains("last_pruned_fact_change_log_id=:pruned"));
    }

    // ---------------------------------------------------------------------------------------------
    // EntityManager gateway binding. Cheap coverage that the production gateway issues each shared
    // constant and binds its parameters; the real concurrency behaviour is proven in the MariaDB IT.
    // ---------------------------------------------------------------------------------------------

    @Test
    void entityManagerGatewayBindsTheSettleAndAdvanceStatements() {
        EntityManager em = mock(EntityManager.class);
        Query snapshot = query();
        Query maxLog = query();
        Query settle = query();
        Query advance = query();
        when(snapshot.getSingleResult()).thenReturn(new Object[]{TEN, ZERO, "READY", null, null});
        when(maxLog.getSingleResult()).thenReturn(valueOf(14));
        when(settle.getResultList()).thenReturn(List.of(14L));
        when(advance.executeUpdate()).thenReturn(1);
        List<String> issued = new ArrayList<>();
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            issued.add(sql);
            if (sql.equals(DeliveryEvidencePoll.POLL_WATERMARK_SNAPSHOT_SQL)) return snapshot;
            if (sql.equals(DeliveryEvidencePoll.LOG_MAX_SQL)) return maxLog;
            if (sql.equals(DeliveryEvidencePoll.LOG_SETTLE_LOCK_SQL)) return settle;
            if (sql.equals(DeliveryEvidencePoll.ADVANCE_SQL)) return advance;
            throw new AssertionError("Unexpected query: " + sql);
        });
        EntityManagerDeliveryPollGateway gateway = new EntityManagerDeliveryPollGateway(em);

        DeliveryWatermark watermark = gateway.snapshot();
        BigInteger max = gateway.maxLogId();
        BigInteger settledTarget = gateway.settleHorizon(TEN, valueOf(14));
        boolean advanced = gateway.advance(TEN, valueOf(14),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), true);

        assertEquals(TEN, watermark.cursor());
        assertTrue(watermark.ready());
        assertEquals(valueOf(14), max);
        assertEquals(valueOf(14), settledTarget);
        assertTrue(advanced);
        verify(settle).setParameter("cursor", TEN);
        verify(settle).setParameter("target", valueOf(14));
        verify(advance).setParameter("target", valueOf(14));
        verify(advance).setParameter("cursor", TEN);
        verify(advance).setParameter("relevant", 1);
        assertTrue(issued.contains(DeliveryEvidencePoll.LOG_MAX_SQL));
        assertTrue(issued.contains(DeliveryEvidencePoll.LOG_SETTLE_LOCK_SQL));
    }

    private static DeliveryWatermark ready(BigInteger cursor, BigInteger pruned) {
        return new DeliveryWatermark(cursor, pruned, "READY", false, null);
    }

    // ---------------------------------------------------------------------------------------------
    // Final attempt scan (EntityManager). A2 fix: log range is locked BEFORE the watermark.
    // ---------------------------------------------------------------------------------------------

    @Test
    void finalScanLocksTheLogRangeBeforeTheWatermarkAndUsesThePublishedAttemptUnion() {
        Fixture fixture = fixture("RUNNING", "published", "attempt", TEN,
                new Object[]{Date.valueOf("2026-05-01"), Date.valueOf("2026-06-01")});

        DeliveryPollResult result = fixture.marker.finalDeliveryEvidenceScan("attempt");

        assertTrue(result.relevant());
        int logIndex = indexOf(fixture.sql, "WHERE id>:cursor ORDER BY id FOR UPDATE");
        int watermarkLockIndex = indexOf(fixture.sql, "WHERE source_name='DELIVERY_EVIDENCE' FOR UPDATE");
        assertTrue(logIndex >= 0 && watermarkLockIndex >= 0);
        assertTrue(logIndex < watermarkLockIndex,
                "A2: the change-log range must be locked before the watermark");

        String boundsSql = fixture.sql.stream()
                .filter(value -> value.contains("SELECT MIN(d.dependent_recognized_month)"))
                .findFirst().orElseThrow();
        assertTrue(boundsSql.contains("d.generation_id=p.published_generation_id"));
        assertTrue(boundsSql.contains("d.generation_id=p.attempt_generation_id"));
        assertTrue(boundsSql.stripTrailing().endsWith("FOR UPDATE"));
        assertFalse(boundsSql.contains("JOIN work live_work"));
        assertTrue(boundsSql.contains("d.source_capacity_user_uuid IS NOT NULL"));
        verify(fixture.logHorizon).setParameter("cursor", TEN);
    }

    @Test
    void finalScanAbortsWhenTheCursorMovedBetweenTheSnapshotAndTheLock() {
        // Snapshot cursor 10, but the watermark lock sees 11 (a mover) -> conflict, attempt retries.
        Fixture fixture = fixture("RUNNING", "published", "attempt", valueOf(11), new Object[]{null, null});

        WatermarkConflictException failure = assertThrows(WatermarkConflictException.class,
                () -> fixture.marker.finalDeliveryEvidenceScan("attempt"));

        assertEquals("DELIVERY_EVIDENCE_CURSOR_CONFLICT", failure.getMessage());
    }

    @Test
    void finalScanRefusesAnotherAttemptOwner() {
        Fixture fixture = fixture("RUNNING", "published", "other", TEN, new Object[]{null, null});

        WatermarkConflictException failure = assertThrows(WatermarkConflictException.class,
                () -> fixture.marker.finalDeliveryEvidenceScan("attempt"));

        assertEquals("REVENUE_ATTEMPT_OWNER_LOST", failure.getMessage());
    }

    @Test
    void finalScanWithNoNewActualRowsKeepsTheExistingCursor() {
        Fixture fixture = fixture("RUNNING", "published", "attempt", TEN, new Object[]{null, null});
        when(fixture.logHorizon.getResultList()).thenReturn(List.of());

        DeliveryPollResult result = fixture.marker.finalDeliveryEvidenceScan("attempt");

        assertFalse(result.relevant());
        assertEquals(TEN, result.observedCursor());
        verify(fixture.bounds, never()).getSingleResult();
        verify(fixture.update, never()).executeUpdate();
    }

    private static int indexOf(List<String> sql, String needle) {
        for (int i = 0; i < sql.size(); i++) {
            if (sql.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static Fixture fixture(String status, String published, String attempt,
                                   BigInteger lockedCursor, Object[] boundsRow) {
        EntityManager em = mock(EntityManager.class);
        Query snapshot = query();
        Query watermarkLock = query();
        Query logHorizon = query();
        Query publication = query();
        Query bounds = query();
        Query update = query();
        when(snapshot.getSingleResult()).thenReturn(new Object[]{TEN, ZERO, "READY", null, null});
        when(watermarkLock.getResultList()).thenReturn(java.util.Collections.singletonList(
                new Object[]{lockedCursor, ZERO, "READY", null, null}));
        when(logHorizon.getResultList()).thenReturn(List.of(14L));
        when(publication.getSingleResult()).thenReturn(new Object[]{status, published, attempt});
        when(bounds.getSingleResult()).thenReturn(boundsRow);
        when(update.executeUpdate()).thenReturn(1);
        List<String> sql = new ArrayList<>();
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            sql.add(value);
            if (value.contains("SELECT last_fact_change_log_id") && value.contains("FOR UPDATE")) {
                return watermarkLock;
            }
            if (value.contains("SELECT last_fact_change_log_id")) return snapshot;
            if (value.contains("WHERE id>:cursor ORDER BY id FOR UPDATE")) return logHorizon;
            if (value.contains("SELECT status,published_generation_id")) return publication;
            if (value.contains("SELECT MIN(d.dependent_recognized_month)")) return bounds;
            if (value.contains("SET last_fact_change_log_id=:target")) return update;
            if (value.contains("SET source_state='FAILED',attempt_token=NULL")) return update;
            throw new AssertionError("Unexpected query: " + value);
        });
        PracticeRevenueDirtyMarker marker = new PracticeRevenueDirtyMarker();
        marker.em = em;
        return new Fixture(marker, em, logHorizon, bounds, update, sql);
    }

    private static Query query() {
        Query query = mock(Query.class);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.nullable(Object.class)))
                .thenReturn(query);
        return query;
    }

    private record Fixture(PracticeRevenueDirtyMarker marker, EntityManager em, Query logHorizon,
                           Query bounds, Query update, List<String> sql) { }
}
