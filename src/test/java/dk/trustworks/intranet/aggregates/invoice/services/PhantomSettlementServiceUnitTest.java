package dk.trustworks.intranet.aggregates.invoice.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhantomSettlementServiceUnitTest {

    @Test
    void listBackfillCandidateInternalUuids_excludesDraftInternals() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(em.createNativeQuery(sql.capture())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        PhantomSettlementService service = new PhantomSettlementService();
        service.em = em;

        service.listBackfillCandidateInternalUuids();

        assertTrue(sql.getValue().contains("i.status IN ('PENDING_REVIEW','QUEUED','CREATED')"),
                "backfill candidates must not include mutable DRAFT internals");
    }
}
