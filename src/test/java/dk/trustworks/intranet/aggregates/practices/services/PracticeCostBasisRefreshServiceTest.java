package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeCostBasisRefreshServiceTest {
    private static final String REQUEST_KEY = "a".repeat(64);
    private static final String INPUT_VECTOR = "b".repeat(64);

    @Test void noChangeIsRestrictedToByteIdenticalIncrementalCandidates(){
        assertTrue(PracticeCostBasisRefreshService.shouldCertifyNoChange("INCREMENTAL_BI","same","same"));
        assertFalse(PracticeCostBasisRefreshService.shouldCertifyNoChange("FULL_BI","same","same"));
        assertFalse(PracticeCostBasisRefreshService.shouldCertifyNoChange("INCREMENTAL_BI","new","old"));
    }

    @Test void onlyByteEquivalentIncrementalMayFinishNoChange(){
        // FULL_BI, PRACTICE_BASIS_INPUT, COST_GL_INPUT and DEPENDENCY_MANIFEST_INPUT must publish a new
        // certified generation even when the consolidated cost fingerprint is byte-identical.
        for(String cause:new String[]{"FULL_BI","PRACTICE_BASIS_INPUT","COST_GL_INPUT",
                "DEPENDENCY_MANIFEST_INPUT"}){
            assertFalse(PracticeCostBasisRefreshService.shouldCertifyNoChange(cause,"same","same"),cause);
        }
        assertTrue(PracticeCostBasisRefreshService.shouldCertifyNoChange("INCREMENTAL_BI","same","same"));
    }

    @Test
    void claimCapturesTheDocumentDependencyFingerprintWhenPresent() {
        EntityManager em = routedEm(new Object[]{
                BigInteger.valueOf(17), REQUEST_KEY, "FULL_BI", INPUT_VECTOR,
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.valueOf(5), "c".repeat(64)}, 1);
        PracticeCostBasisRefreshService service = new PracticeCostBasisRefreshService();
        service.em = em;

        var claimed = service.claimExpectedPending(new PracticeCostBasisRefreshService.ExpectedRequest(
                BigInteger.valueOf(17), REQUEST_KEY, INPUT_VECTOR));

        assertEquals("c".repeat(64), claimed.capturedDependencyFingerprint());
    }

    @Test
    void displacedRunningRequestIsRetiredAsSupersededPointingAtItsSuccessor() {
        EntityManager em = mock(EntityManager.class);
        Query basisSelect = query();
        Query supersede = query();
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT generation_id FROM practice_basis_generation")) return basisSelect;
            return supersede;
        });
        when(basisSelect.getResultList()).thenReturn(List.of());
        when(supersede.executeUpdate()).thenReturn(1);
        PracticeCostBasisRefreshService service = new PracticeCostBasisRefreshService();
        service.em = em;

        service.supersedeAndCleanup(claim(BigInteger.valueOf(17)), BigInteger.valueOf(42));

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(em, org.mockito.Mockito.atLeastOnce()).createNativeQuery(sql.capture());
        String joined = String.join("\n", sql.getAllValues());
        assertTrue(joined.contains("status='SUPERSEDED'"));
        assertTrue(joined.contains("superseded_by_request_id=:successor"));
        assertTrue(joined.contains(":successor > request_id"));
        verify(supersede).setParameter("successor", BigInteger.valueOf(42));
    }

    @Test
    void aMissingSuccessorFallsBackToTechnicalFailedAndNeverSelfLinks() {
        EntityManager em = mock(EntityManager.class);
        Query basisSelect = query();
        Query failUpdate = query();
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT generation_id FROM practice_basis_generation")) return basisSelect;
            return failUpdate;
        });
        when(basisSelect.getResultList()).thenReturn(List.of());
        when(failUpdate.executeUpdate()).thenReturn(1);
        PracticeCostBasisRefreshService service = new PracticeCostBasisRefreshService();
        service.em = em;

        // A self-referencing successor is refused: the request cannot supersede itself.
        service.supersedeAndCleanup(claim(BigInteger.valueOf(17)), BigInteger.valueOf(17));

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(em, org.mockito.Mockito.atLeastOnce()).createNativeQuery(sql.capture());
        String joined = String.join("\n", sql.getAllValues());
        assertTrue(joined.contains("status='FAILED'"));
        assertFalse(joined.contains("status='SUPERSEDED'"));
    }

    @Test
    void technicalRetryTransitionsTheSameRowFailedToPendingWithAttemptIncrement() {
        EntityManager em = mock(EntityManager.class);
        Query retry = query();
        when(em.createNativeQuery(anyString())).thenReturn(retry);
        when(retry.executeUpdate()).thenReturn(1);
        PracticeCostBasisRefreshService service = new PracticeCostBasisRefreshService();
        service.em = em;

        int updated = service.retryTechnicalFailure(BigInteger.valueOf(17), 4L);

        assertEquals(1, updated);
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        String joined = sql.getValue();
        assertTrue(joined.contains("r.status='PENDING'"));
        assertTrue(joined.contains("r.status='FAILED' AND r.optimistic_version=:version"));
        assertTrue(joined.contains("r.attempt_count=r.attempt_count+1"));
        // Only retried while it remains the latest request; a dominated failure is never retried.
        assertTrue(joined.contains("newer.request_id > r.request_id"));
        verify(retry).setParameter("version", 4L);
    }

    @Test void supersededExceptionCarriesTheSuccessorAndManifestMissCarriesBounds() {
        var superseded = new PracticeCostBasisRefreshService.CostRequestSupersededException(
                BigInteger.valueOf(88));
        assertEquals(BigInteger.valueOf(88), superseded.successorRequestId());
        var miss = new PracticeCostBasisRefreshService.DependencyManifestMissException(
                java.time.LocalDate.parse("2021-01-01"), java.time.LocalDate.parse("2026-12-31"), "f".repeat(64));
        assertEquals(java.time.LocalDate.parse("2021-01-01"), miss.affectedStart());
        assertEquals("f".repeat(64), miss.manifestFingerprint());
    }

    private static PracticeCostBasisRefreshService.Claim claim(BigInteger requestId) {
        return new PracticeCostBasisRefreshService.Claim(requestId, REQUEST_KEY, "FULL_BI", INPUT_VECTOR,
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(4),
                BigInteger.valueOf(5), null, BigInteger.valueOf(9), "owner-token");
    }

    private static EntityManager routedEm(Object[] requestRow, int claimUpdated) {
        EntityManager em = mock(EntityManager.class);
        Query recovery = query();
        Query control = query();
        Query request = query();
        Query claim = query();
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT revenue_recovery_owner_token")) return recovery;
            if (sql.contains("SELECT refresh_enabled")) return control;
            if (sql.contains("SELECT request_id")) return request;
            return claim;
        });
        when(recovery.getSingleResult()).thenReturn(null);
        when(control.getSingleResult()).thenReturn(new Object[]{true, BigInteger.valueOf(9)});
        when(request.getResultList()).thenReturn(java.util.Collections.singletonList(requestRow));
        when(claim.executeUpdate()).thenReturn(claimUpdated);
        return em;
    }

    @Test
    void exactClaimBindsIdKeyAndVectorAndCannotWidenToANewerPendingRequest() {
        EntityManager em = mock(EntityManager.class);
        Query recovery = query();
        Query control = query();
        Query request = query();
        Query claim = query();
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT revenue_recovery_owner_token")) return recovery;
            if (sql.contains("SELECT refresh_enabled")) return control;
            if (sql.contains("SELECT request_id")) return request;
            return claim;
        });
        when(recovery.getSingleResult()).thenReturn(null);
        when(control.getSingleResult()).thenReturn(new Object[]{true, BigInteger.valueOf(9)});
        when(request.getResultList()).thenReturn(java.util.Collections.singletonList(new Object[]{
                BigInteger.valueOf(17), REQUEST_KEY, "FULL_BI", INPUT_VECTOR,
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.valueOf(5)}));
        when(claim.executeUpdate()).thenReturn(1);
        PracticeCostBasisRefreshService service = new PracticeCostBasisRefreshService();
        service.em = em;
        var expected = new PracticeCostBasisRefreshService.ExpectedRequest(
                BigInteger.valueOf(17), REQUEST_KEY, INPUT_VECTOR);

        var claimed = service.claimExpectedPending(expected);

        assertEquals(BigInteger.valueOf(17), claimed.requestId());
        assertEquals(REQUEST_KEY, claimed.requestKey());
        assertEquals(INPUT_VECTOR, claimed.inputVector());
        verify(request).setParameter("requestId", BigInteger.valueOf(17));
        verify(request).setParameter("requestKey", REQUEST_KEY);
        verify(request).setParameter("inputVector", INPUT_VECTOR);
        verify(claim).setParameter("id", BigInteger.valueOf(17));
        verify(claim).setParameter("requestKey", REQUEST_KEY);
        verify(claim).setParameter("inputVector", INPUT_VECTOR);
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(em, org.mockito.Mockito.atLeastOnce()).createNativeQuery(sql.capture());
        String joined = String.join("\n", sql.getAllValues());
        assertTrue(joined.contains("p.latest_cost_basis_request_id=r.request_id"));
        assertTrue(joined.contains("newer.request_id>r.request_id"));
    }

    @Test
    void mismatchedExpectedIdentityIsRefusedWithoutClaimingAnyOtherRequest() {
        EntityManager em = mock(EntityManager.class);
        Query recovery = query();
        Query control = query();
        Query request = query();
        Query claim = query();
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT revenue_recovery_owner_token")) return recovery;
            if (sql.contains("SELECT refresh_enabled")) return control;
            if (sql.contains("SELECT request_id")) return request;
            return claim;
        });
        when(recovery.getSingleResult()).thenReturn(null);
        when(control.getSingleResult()).thenReturn(new Object[]{true, BigInteger.valueOf(9)});
        when(request.getResultList()).thenReturn(List.of());
        PracticeCostBasisRefreshService service = new PracticeCostBasisRefreshService();
        service.em = em;

        var claimed = service.claimExpectedPending(new PracticeCostBasisRefreshService.ExpectedRequest(
                BigInteger.valueOf(17), REQUEST_KEY, INPUT_VECTOR));

        assertNull(claimed);
        verify(claim, never()).executeUpdate();
    }

    private static Query query() {
        Query query = mock(Query.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        return query;
    }
}
