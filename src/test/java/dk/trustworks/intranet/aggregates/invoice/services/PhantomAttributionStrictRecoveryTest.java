package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.enums.PhantomDerivationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.ArgumentCaptor;

class PhantomAttributionStrictRecoveryTest {
    private static final LocalDate FROM=LocalDate.parse("2021-07-01");
    private static final LocalDate TO=LocalDate.parse("2026-06-30");

    @Test
    void processesAndValidatesTheExactStableDependencySetWithoutSwallowingItems(){
        PhantomAttributionService proxy=mock(PhantomAttributionService.class);
        PhantomAttributionService service=new PhantomAttributionService();
        service.self=proxy;
        when(proxy.listRecognitionRangeUuids(FROM,TO))
                .thenReturn(List.of("a","b"),List.of("a","b"));
        when(proxy.deriveForPhantomRecoveryDependency("a"))
                .thenReturn(PhantomDerivationStatus.ATTRIBUTED);
        when(proxy.deriveForPhantomRecoveryDependency("b"))
                .thenReturn(PhantomDerivationStatus.SKIPPED_MANUAL);

        PhantomAttributionService.StrictRangeResult result=service.deriveRangeStrict(FROM,TO);

        assertEquals(2,result.dependencyCount());
        assertEquals(1,result.statusCounts().get(PhantomDerivationStatus.ATTRIBUTED));
        var order=inOrder(proxy);
        order.verify(proxy).listRecognitionRangeUuids(FROM,TO);
        order.verify(proxy).deriveForPhantomRecoveryDependency("a");
        order.verify(proxy).validateStrictOutcome("a",PhantomDerivationStatus.ATTRIBUTED);
        order.verify(proxy).deriveForPhantomRecoveryDependency("b");
        order.verify(proxy).validateStrictOutcome("b",PhantomDerivationStatus.SKIPPED_MANUAL);
        order.verify(proxy).listRecognitionRangeUuids(FROM,TO);
        verifyNoMoreInteractions(proxy);
    }

    @Test
    void oneItemFailureAbortsTheWholeStrictRangeBeforeReadyValidation(){
        PhantomAttributionService proxy=mock(PhantomAttributionService.class);
        PhantomAttributionService service=new PhantomAttributionService();
        service.self=proxy;
        when(proxy.listRecognitionRangeUuids(FROM,TO)).thenReturn(List.of("a","b"));
        when(proxy.deriveForPhantomRecoveryDependency("a"))
                .thenReturn(PhantomDerivationStatus.ATTRIBUTED);
        doThrow(new IllegalStateException("item failed"))
                .when(proxy).validateStrictOutcome("a",PhantomDerivationStatus.ATTRIBUTED);

        assertThrows(IllegalStateException.class,()->service.deriveRangeStrict(FROM,TO));

        verify(proxy).listRecognitionRangeUuids(FROM,TO);
        verify(proxy).deriveForPhantomRecoveryDependency("a");
        verify(proxy).validateStrictOutcome("a",PhantomDerivationStatus.ATTRIBUTED);
        verifyNoMoreInteractions(proxy);
    }

    @Test
    void dependencyPopulationIncludesExactImmutableOneHopHistoricalPhantoms(){
        EntityManager em=mock(EntityManager.class);
        Query query=mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(),any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        PhantomAttributionService service=new PhantomAttributionService();
        service.em=em;

        service.listRecognitionRangeUuids(FROM,TO);

        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        String normalized=sql.getValue().toLowerCase();
        org.junit.jupiter.api.Assertions.assertTrue(normalized.contains(
                "practice_basis_dependency_manifest_mat"));
        org.junit.jupiter.api.Assertions.assertTrue(normalized.contains("source_document_uuid"));
        org.junit.jupiter.api.Assertions.assertTrue(normalized.contains("recognized_document_type='credit_note'"));
    }
}
