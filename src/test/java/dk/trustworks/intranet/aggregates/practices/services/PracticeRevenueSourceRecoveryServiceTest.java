package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.math.BigInteger;
import java.util.Collections;
import java.util.EnumSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeRevenueSourceRecoveryServiceTest {

    private static final String TOKEN = "11111111-1111-1111-1111-111111111111";

    @Test
    void categoryAllowListAndParserAreClosedToExactlySixUppercaseValues() {
        assertEquals(EnumSet.of(
                        PracticeRevenueSourceRecoveryService.Category.PUBLICATION,
                        PracticeRevenueSourceRecoveryService.Category.COST_BASIS,
                        PracticeRevenueSourceRecoveryService.Category.FINANCE_GL,
                        PracticeRevenueSourceRecoveryService.Category.SELF_BILLED,
                        PracticeRevenueSourceRecoveryService.Category.PHANTOM_ATTRIBUTION,
                        PracticeRevenueSourceRecoveryService.Category.DELIVERY_EVIDENCE),
                EnumSet.allOf(PracticeRevenueSourceRecoveryService.Category.class));
        assertEquals(PracticeRevenueSourceRecoveryService.Category.FINANCE_GL,
                PracticeRevenueSourceRecoveryService.Category.fromPath("FINANCE_GL"));
        assertThrows(IllegalArgumentException.class,
                () -> PracticeRevenueSourceRecoveryService.Category.fromPath("finance_gl"));
        assertThrows(IllegalArgumentException.class,
                () -> PracticeRevenueSourceRecoveryService.Category.fromPath("INVOICE_DOCUMENT"));
        assertThrows(IllegalArgumentException.class,
                () -> PracticeRevenueSourceRecoveryService.Category.fromPath(""));
    }

    @Test
    void recoveryWindowUsesSixtyCompletedCopenhagenMonthsAcrossUtcYearBoundary(){
        Clock beforeCopenhagenMidnight=Clock.fixed(Instant.parse("2026-12-31T22:30:00Z"),ZoneOffset.UTC);
        Clock afterCopenhagenMidnight=Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"),ZoneOffset.UTC);

        assertEquals(new PracticeRevenueSourceRecoveryService.RecoveryWindow(
                java.time.LocalDate.parse("2021-12-01"),java.time.LocalDate.parse("2026-11-30")),
                PracticeRevenueSourceRecoveryService.recoveryWindow(beforeCopenhagenMidnight));
        assertEquals(new PracticeRevenueSourceRecoveryService.RecoveryWindow(
                java.time.LocalDate.parse("2022-01-01"),java.time.LocalDate.parse("2026-12-31")),
                PracticeRevenueSourceRecoveryService.recoveryWindow(afterCopenhagenMidnight));
    }

    @Test
    void publicationRecoveryAcquiresProofOfDeathLockAndReleasesWithinFiveSecondBound() {
        EntityManager em = mock(EntityManager.class);
        Query acquire = query();
        Query owner = query();
        Query fail = query();
        Query delete = query();
        Query release = query();
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.ACQUIRE_LOCK_SQL)).thenReturn(acquire);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.PUBLICATION_OWNER_SQL)).thenReturn(owner);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.PUBLICATION_FAIL_SQL)).thenReturn(fail);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.DELETE_ATTEMPT_SQL)).thenReturn(delete);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.RELEASE_LOCK_SQL)).thenReturn(release);
        when(acquire.getSingleResult()).thenReturn(1);
        when(owner.getSingleResult()).thenReturn(new Object[]{"RUNNING", TOKEN, "attempt-1", 1_801L});
        when(fail.executeUpdate()).thenReturn(1);
        when(delete.executeUpdate()).thenReturn(4);
        when(release.getSingleResult()).thenReturn(1);
        PracticeRevenueSourceRecoveryService service = service(em);

        PracticeRevenueSourceRecoveryService.RecoveryResult result = service.recover(
                PracticeRevenueSourceRecoveryService.Category.PUBLICATION, TOKEN);

        assertEquals(new PracticeRevenueSourceRecoveryService.RecoveryResult(
                "PUBLICATION", 1, "attempt-1"), result);
        InOrder order = inOrder(em, acquire, owner, fail, delete, release);
        order.verify(em).createNativeQuery(PracticeRevenueSourceRecoveryService.ACQUIRE_LOCK_SQL);
        order.verify(acquire).getSingleResult();
        order.verify(em).createNativeQuery(PracticeRevenueSourceRecoveryService.PUBLICATION_OWNER_SQL);
        order.verify(owner).getSingleResult();
        order.verify(fail).executeUpdate();
        order.verify(delete).executeUpdate();
        order.verify(em).createNativeQuery(PracticeRevenueSourceRecoveryService.RELEASE_LOCK_SQL);
        order.verify(release).setHint("jakarta.persistence.query.timeout",
                PracticeRevenueSourceRecoveryService.LOCK_RELEASE_TIMEOUT_MS);
        order.verify(release).getSingleResult();
    }

    @Test
    void heldOwnerLockIsAConflictAndOwnerRowIsNeverTouched() {
        EntityManager em = mock(EntityManager.class);
        Query acquire = query();
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.ACQUIRE_LOCK_SQL)).thenReturn(acquire);
        when(acquire.getSingleResult()).thenReturn(0);
        PracticeRevenueSourceRecoveryService service = service(em);

        PracticeRevenueSourceRecoveryService.RecoveryConflictException conflict = assertThrows(
                PracticeRevenueSourceRecoveryService.RecoveryConflictException.class,
                () -> service.recover(PracticeRevenueSourceRecoveryService.Category.PUBLICATION, TOKEN));

        assertEquals("OWNER_LOCK_STILL_HELD", conflict.getMessage());
        verify(em, never()).createNativeQuery(PracticeRevenueSourceRecoveryService.PUBLICATION_OWNER_SQL);
        verify(em, never()).createNativeQuery(PracticeRevenueSourceRecoveryService.RELEASE_LOCK_SQL);
    }

    @Test
    void nonExpiredOrMismatchedOwnerIsNeverClearedAndRecoveryLockIsStillReleased() {
        EntityManager em = mock(EntityManager.class);
        Query acquire = query();
        Query owner = query();
        Query release = query();
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.ACQUIRE_LOCK_SQL)).thenReturn(acquire);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.PUBLICATION_OWNER_SQL)).thenReturn(owner);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.RELEASE_LOCK_SQL)).thenReturn(release);
        when(acquire.getSingleResult()).thenReturn(1);
        when(owner.getSingleResult()).thenReturn(new Object[]{"RUNNING", TOKEN, "attempt-1", 1_799L});
        when(release.getSingleResult()).thenReturn(1);
        PracticeRevenueSourceRecoveryService service = service(em);

        PracticeRevenueSourceRecoveryService.RecoveryConflictException conflict = assertThrows(
                PracticeRevenueSourceRecoveryService.RecoveryConflictException.class,
                () -> service.recover(PracticeRevenueSourceRecoveryService.Category.PUBLICATION, TOKEN));

        assertEquals("OWNER_NOT_EXPIRED", conflict.getMessage());
        verify(em, never()).createNativeQuery(PracticeRevenueSourceRecoveryService.PUBLICATION_FAIL_SQL);
        verify(release).getSingleResult();
    }

    @Test
    void costCleanupIsTokenGuardedAndLeavesAForwardFailedRequestRatherThanUnsafeRetry() {
        EntityManager em = mock(EntityManager.class);
        Query acquire = query();
        Query owner = query();
        Query fail = query();
        Query release = query();
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.ACQUIRE_LOCK_SQL)).thenReturn(acquire);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.COST_OWNER_SQL)).thenReturn(owner);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.COST_FAIL_SQL)).thenReturn(fail);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.RELEASE_LOCK_SQL)).thenReturn(release);
        when(acquire.getSingleResult()).thenReturn(1);
        when(owner.getResultList()).thenReturn(Collections.singletonList(
                new Object[]{"RUNNING", TOKEN, 9L, 1_801L}));
        when(fail.executeUpdate()).thenReturn(1);
        when(release.getSingleResult()).thenReturn(1);
        PracticeRevenueSourceRecoveryService service = service(em);

        PracticeRevenueSourceRecoveryService.RecoveryResult result = service.recover(
                PracticeRevenueSourceRecoveryService.Category.COST_BASIS, TOKEN);

        assertEquals("9", result.recoveredIdentity());
        assertTrue(PracticeRevenueSourceRecoveryService.COST_FAIL_SQL.contains("status='FAILED'"));
        verify(fail).setParameter("token", TOKEN);
        verify(release).getSingleResult();
    }

    @Test
    void recoveryOwnerSpansCleanupRebuildAndSingleReadyCompletion() {
        EntityManager em = mock(EntityManager.class);
        Query acquire = query();
        Query owner = query();
        Query claim = query();
        Query release = query();
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.ACQUIRE_LOCK_SQL)).thenReturn(acquire);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.SOURCE_OWNER_SQL)).thenReturn(owner);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.SOURCE_RECOVERY_CLAIM_SQL)).thenReturn(claim);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.RELEASE_LOCK_SQL)).thenReturn(release);
        when(acquire.getSingleResult()).thenReturn(1);
        when(owner.getSingleResult()).thenReturn(new Object[]{"RUNNING", TOKEN, null, 1_801L});
        when(claim.executeUpdate()).thenReturn(1);
        when(release.getSingleResult()).thenReturn(1);
        PracticeRevenueSourceRebuildHandler handler = mock(PracticeRevenueSourceRebuildHandler.class);
        when(handler.category()).thenReturn(PracticeRevenueSourceRecoveryService.Category.SELF_BILLED);
        when(handler.rebuild(any())).thenReturn(PracticeRevenueSourceRebuildHandler.RebuildResult.success());
        PracticeRevenueSourceRecoveryService service = service(em, handler);

        PracticeRevenueSourceRecoveryService.RecoveryResult result = service.recover(
                PracticeRevenueSourceRecoveryService.Category.SELF_BILLED, TOKEN);

        assertEquals("SELF_BILLED", result.category());
        assertEquals("REBUILT:SELF_BILLED", result.recoveredIdentity());
        verify(owner).setParameter("source", "SELF_BILLED");
        verify(claim).setParameter("source","SELF_BILLED");
        verify(claim).setParameter("staleToken",TOKEN);
        assertTrue(PracticeRevenueSourceRecoveryService.SOURCE_RECOVERY_CLAIM_SQL
                .contains("source_state='RUNNING'"));
        verify(release).getSingleResult();
        ArgumentCaptor<PracticeRevenueSourceRebuildHandler.RebuildRequest> request=
                ArgumentCaptor.forClass(PracticeRevenueSourceRebuildHandler.RebuildRequest.class);
        verify(handler).rebuild(request.capture());
        assertEquals(java.time.LocalDate.parse("2021-07-01"),request.getValue().fromInclusive());
        assertEquals(java.time.LocalDate.parse("2026-06-30"),request.getValue().toInclusive());
        verify(service.dirtyMarker).completeRecovery(PracticeRevenueDirtyMarker.Source.SELF_BILLED,
                request.getValue().recoveryToken(),YearMonth.parse("2021-07"),YearMonth.parse("2026-06"),null);
    }

    @Test
    void unavailableAuthoritativeSourceHandlerFailsClosedAfterTokenGuardedCleanup() {
        EntityManager em = mock(EntityManager.class);
        Query acquire = query();
        Query owner = query();
        Query claim = query();
        Query release = query();
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.ACQUIRE_LOCK_SQL)).thenReturn(acquire);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.SOURCE_OWNER_SQL)).thenReturn(owner);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.SOURCE_RECOVERY_CLAIM_SQL)).thenReturn(claim);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.RELEASE_LOCK_SQL)).thenReturn(release);
        when(acquire.getSingleResult()).thenReturn(1);
        when(owner.getSingleResult()).thenReturn(new Object[]{"RUNNING", TOKEN, null, 1_801L});
        when(claim.executeUpdate()).thenReturn(1);
        when(release.getSingleResult()).thenReturn(1);
        PracticeRevenueSourceRecoveryService service = service(em);

        PracticeRevenueSourceRecoveryService.RecoveryConflictException conflict = assertThrows(
                PracticeRevenueSourceRecoveryService.RecoveryConflictException.class,
                () -> service.recover(PracticeRevenueSourceRecoveryService.Category.FINANCE_GL, TOKEN));

        assertEquals("SOURCE_REBUILD_UNAVAILABLE", conflict.getMessage());
        verify(claim).executeUpdate();
        verify(release).getSingleResult();
        verify(service.dirtyMarker).failRecovery(eq(PracticeRevenueDirtyMarker.Source.FINANCE_GL),anyString());
    }

    @Test
    void deliveryRecoveryClaimsOnlyDisabledRetentionGapUnderBiRevenueAndDeliveryLocks(){
        EntityManager em=mock(EntityManager.class);
        Query acquireOne=query(),acquireTwo=query(),acquireThree=query();
        Query releaseOne=query(),releaseTwo=query(),releaseThree=query();
        Query precondition=query(),target=query(),claim=query();
        // bi_refresh coordinates with stored procedures and stays server-global; the two
        // application-owned locks go through the schema-scoped SQL.
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.ACQUIRE_GLOBAL_LOCK_SQL))
                .thenReturn(acquireOne);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.ACQUIRE_LOCK_SQL))
                .thenReturn(acquireTwo,acquireThree);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.RELEASE_LOCK_SQL))
                .thenReturn(releaseOne,releaseTwo);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.RELEASE_GLOBAL_LOCK_SQL))
                .thenReturn(releaseThree);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.DELIVERY_RECOVERY_PRECONDITION_SQL))
                .thenReturn(precondition);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.DELIVERY_TARGET_SQL)).thenReturn(target);
        when(em.createNativeQuery(PracticeRevenueSourceRecoveryService.DELIVERY_RECOVERY_CLAIM_SQL)).thenReturn(claim);
        when(acquireOne.getSingleResult()).thenReturn(1);
        when(acquireTwo.getSingleResult()).thenReturn(1);
        when(acquireThree.getSingleResult()).thenReturn(1);
        when(releaseOne.getSingleResult()).thenReturn(1);
        when(releaseTwo.getSingleResult()).thenReturn(1);
        when(releaseThree.getSingleResult()).thenReturn(1);
        when(precondition.getSingleResult()).thenReturn(new Object[]{"FAILED","FACT_CHANGE_LOG_RETENTION_GAP",
                "FACT_CHANGE_LOG_RETENTION_GAP",null,null,false,null,8L,8L});
        when(target.getSingleResult()).thenReturn(BigInteger.TEN);
        when(claim.executeUpdate()).thenReturn(1);
        PracticeRevenueSourceRebuildHandler handler=mock(PracticeRevenueSourceRebuildHandler.class);
        when(handler.category()).thenReturn(PracticeRevenueSourceRecoveryService.Category.DELIVERY_EVIDENCE);
        when(handler.rebuild(any())).thenReturn(
                PracticeRevenueSourceRebuildHandler.RebuildResult.deliverySuccess(BigInteger.valueOf(12)));
        PracticeRevenueSourceRecoveryService service=service(em,handler);

        PracticeRevenueSourceRecoveryService.RecoveryResult result=service.recover(
                PracticeRevenueSourceRecoveryService.Category.DELIVERY_EVIDENCE,null);

        assertEquals("REBUILT:DELIVERY_EVIDENCE",result.recoveredIdentity());
        verify(acquireOne).setParameter("lock","bi_refresh");
        verify(acquireTwo).setParameter("lock","practice_revenue");
        verify(acquireThree).setParameter("lock","practice_revenue_source_delivery_evidence");
        ArgumentCaptor<PracticeRevenueSourceRebuildHandler.RebuildRequest> request=
                ArgumentCaptor.forClass(PracticeRevenueSourceRebuildHandler.RebuildRequest.class);
        verify(handler).rebuild(request.capture());
        assertEquals(BigInteger.TEN,request.getValue().recoveryTargetFactChangeLogId());
        verify(service.dirtyMarker).completeRecovery(PracticeRevenueDirtyMarker.Source.DELIVERY_EVIDENCE,
                request.getValue().recoveryToken(),YearMonth.parse("2021-07"),YearMonth.parse("2026-06"),
                BigInteger.valueOf(12));
    }

    @Test
    void deliveryRecoveryRejectsAnyCallerSuppliedOwnerTokenBeforeDatabaseAccess(){
        EntityManager em=mock(EntityManager.class);
        PracticeRevenueSourceRecoveryService service=service(em);
        assertThrows(IllegalArgumentException.class,()->service.recover(
                PracticeRevenueSourceRecoveryService.Category.DELIVERY_EVIDENCE,TOKEN));
        verify(em,never()).createNativeQuery(anyString());
    }

    @Test
    void malformedTokenOrDurationFailsBeforeAnyOwnerCleanup() {
        EntityManager em = mock(EntityManager.class);
        PracticeRevenueSourceRecoveryService service = service(em);
        assertThrows(IllegalArgumentException.class,
                () -> service.recover(PracticeRevenueSourceRecoveryService.Category.PUBLICATION, "not-a-token"));
        service.staleAfter = Duration.ZERO;
        assertThrows(IllegalStateException.class,
                () -> service.recover(PracticeRevenueSourceRecoveryService.Category.PUBLICATION, TOKEN));
        service.staleAfter = Duration.ofMillis(500);
        assertThrows(IllegalStateException.class,
                () -> service.recover(PracticeRevenueSourceRecoveryService.Category.PUBLICATION, TOKEN));
        verify(em, never()).createNativeQuery(anyString());
    }

    private static PracticeRevenueSourceRecoveryService service(EntityManager em) {
        return service(em, new PracticeRevenueSourceRebuildHandler[0]);
    }

    @SafeVarargs
    private static PracticeRevenueSourceRecoveryService service(
            EntityManager em, PracticeRevenueSourceRebuildHandler... handlers) {
        PracticeRevenueSourceRecoveryService service = new PracticeRevenueSourceRecoveryService();
        service.em = em;
        service.staleAfter = Duration.ofMinutes(30);
        service.clock = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
        service.dirtyMarker=mock(PracticeRevenueDirtyMarker.class);
        @SuppressWarnings("unchecked")
        Instance<PracticeRevenueSourceRebuildHandler> instance = mock(Instance.class);
        when(instance.stream()).thenReturn(Stream.of(handlers));
        service.rebuildHandlers = instance;
        return service;
    }

    private static Query query() {
        Query query = mock(Query.class);
        when(query.setHint(anyString(), any())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        return query;
    }
}
