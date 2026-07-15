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
