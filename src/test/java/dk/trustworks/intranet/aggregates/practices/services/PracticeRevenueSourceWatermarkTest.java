package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PracticeRevenueSourceWatermarkTest {
    @Test
    void advancedAndFailedSourcesRemainDirtyAndCostEvidenceRoutesCostFirst() {
        EntityManager em = mock(EntityManager.class);
        Query publication = mock(Query.class);
        Query watermarks = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("FROM practice_revenue_publication")
                        ? publication : watermarks);
        Object[] published = new Object[10];
        published[0] = "READY";
        java.util.Arrays.fill(published, 1, published.length, BigInteger.ONE);
        when(publication.getSingleResult()).thenReturn(published);

        List<Object[]> live = new ArrayList<>();
        for (PracticeRevenueDirtyMarker.Source source : PracticeRevenueDirtyMarker.Source.values()) {
            BigInteger version = source == PracticeRevenueDirtyMarker.Source.INVOICE_DOCUMENT
                    ? BigInteger.TWO : BigInteger.ONE;
            String state = source == PracticeRevenueDirtyMarker.Source.FINANCE_GL ? "FAILED" : "READY";
            live.add(new Object[]{source.name(), version, state});
        }
        when(watermarks.getResultList()).thenReturn((List) live);
        PracticeRevenueDirtyMarker marker = new PracticeRevenueDirtyMarker();
        marker.em = em;

        var state = marker.state();

        assertTrue(state.dirty());
        assertTrue(state.costFirstRequired());
        assertEquals(BigInteger.TWO,
                state.dirtyVersions().get(PracticeRevenueDirtyMarker.Source.INVOICE_DOCUMENT));
        assertEquals(BigInteger.ONE,
                state.dirtyVersions().get(PracticeRevenueDirtyMarker.Source.FINANCE_GL));
    }

    @Test
    void frozenSourceOrderMatchesAllNinePublicationColumns() {
        assertEquals(List.of(
                        "INVOICE_DOCUMENT", "FINANCE_GL", "CURRENCY", "ACCOUNT_CLASSIFICATION",
                        "INVOICE_ATTRIBUTION", "SELF_BILLED", "PHANTOM_ATTRIBUTION",
                        "DELIVERY_EVIDENCE", "PRACTICE_BASIS_INPUT"),
                java.util.Arrays.stream(PracticeRevenueDirtyMarker.Source.values())
                        .map(Enum::name).toList());
    }

    @Test
    void secondAsyncMutationDuringRunningIsDurablyCoalescedWithoutAdvancingSourceVersion() {
        EntityManager em = mock(EntityManager.class);
        Query select = mock(Query.class);
        Query update = mock(Query.class);
        Query insert = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT async_mutation_sequence")) return select;
            if (sql.contains("INSERT INTO practice_revenue_async_mutation_attempt")) return insert;
            return update;
        });
        when(select.setParameter(anyString(), any())).thenReturn(select);
        when(update.setParameter(anyString(), any())).thenReturn(update);
        when(insert.setParameter(anyString(), any())).thenReturn(insert);
        when(select.getResultList()).thenReturn(
                java.util.Collections.singletonList(new Object[]{BigInteger.ZERO, "READY"}),
                java.util.Collections.singletonList(new Object[]{BigInteger.ONE, "RUNNING"}));
        when(update.executeUpdate()).thenReturn(1);
        when(insert.executeUpdate()).thenReturn(1);
        PracticeRevenueDirtyMarker marker = new PracticeRevenueDirtyMarker();
        marker.em = em;

        String first = marker.beginAsyncMutation(
                PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION);
        String second = marker.beginAsyncMutation(
                PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(em, org.mockito.Mockito.times(6)).createNativeQuery(sql.capture());
        assertNotEquals(first, second);
        String cohortUpdate = sql.getAllValues().stream()
                .filter(value -> value.contains("async_pending_count=async_pending_count+1"))
                .findFirst().orElseThrow();
        assertTrue(cohortUpdate.contains("async_mutation_sequence=:sequence"));
        assertTrue(cohortUpdate.contains("async_mutation_sequence=:previousSequence"));
        assertFalse(cohortUpdate.contains("source_state <> 'RUNNING'"));
        assertTrue(sql.getAllValues().stream().noneMatch(value ->
                value.contains("source_version=source_version+1")));
    }

    @Test
    void outOfOrderCompletionCanReachReadyOnlyAfterPendingAndSequenceClosure() {
        EntityManager em = mock(EntityManager.class);
        Query attemptSelect = mock(Query.class);
        Query mutation = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("SELECT mutation_sequence")
                        ? attemptSelect : mutation);
        when(attemptSelect.setParameter(anyString(), any())).thenReturn(attemptSelect);
        when(mutation.setParameter(anyString(), any())).thenReturn(mutation);
        when(attemptSelect.getResultList()).thenReturn(
                java.util.Collections.singletonList(new Object[]{BigInteger.TWO, "RUNNING"}),
                java.util.Collections.singletonList(new Object[]{BigInteger.ONE, "RUNNING"}));
        when(mutation.executeUpdate()).thenReturn(1);
        PracticeRevenueDirtyMarker marker = new PracticeRevenueDirtyMarker();
        marker.em = em;

        marker.completeAsyncMutation(
                PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION, "owner-2");
        marker.completeAsyncMutation(
                PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION, "owner-1");

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(em, org.mockito.Mockito.times(10)).createNativeQuery(sql.capture());
        String joined = String.join("\n", sql.getAllValues());
        assertTrue(joined.contains("async_pending_count=async_pending_count-1"));
        assertTrue(joined.contains("async_completed_sequence=GREATEST(async_completed_sequence,:sequence)"));
        assertTrue(joined.contains("async_pending_count=0"));
        assertTrue(joined.contains("async_completed_sequence=async_mutation_sequence"));
        assertTrue(joined.contains("ASYNC_MUTATION_SEQUENCE_GAP"));
        assertTrue(joined.contains("async_completed_sequence<>async_mutation_sequence"));
        assertFalse(joined.contains("source_version=source_version+1"));
    }
}
