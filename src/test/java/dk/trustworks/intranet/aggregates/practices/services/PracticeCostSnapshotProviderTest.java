package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeCostSnapshotProviderTest {
    @Test
    void disabledIntegrityGatePreservesLegacyShapeButWithholdsEveryFinancialMetric() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        CxoPracticeOperatingCostService reader = mock(CxoPracticeOperatingCostService.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        Object[] disabled = new Object[26];
        disabled[0] = false;
        when(query.getResultList()).thenReturn(Collections.singletonList(disabled));
        PracticeCostSnapshotProvider provider = provider(em, reader);

        var snapshot = provider.getSnapshot(CostSource.BOOKED);

        assertFalse(snapshot.servingEnabled());
        PracticeOperatingCostResponseDTO response = snapshot.response();
        assertEquals(CxoPracticeOperatingCostService.PRACTICES.size(), response.practices().size());
        assertEquals("UNAVAILABLE", response.completenessStatus());
        response.practices().forEach(row -> {
            assertNull(row.currentSalaryDkk());
            assertNull(row.currentOpexDkk());
            assertNull(row.currentTotalDkk());
            assertNull(row.currentAverageFte());
            assertNull(row.currentCostPerFteDkk());
        });
        verify(reader, never()).readPublishedSnapshot(any());
    }

    @Test
    void publicationChangeDuringLegacyBootstrapReadFailsClosed() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        CxoPracticeOperatingCostService reader = mock(CxoPracticeOperatingCostService.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        Object[] before = legacyPointer("2026-01-01 00:00:00");
        Object[] after = legacyPointer("2026-01-02 00:00:00");
        when(query.getResultList()).thenReturn(
                Collections.singletonList(before), Collections.singletonList(after));
        when(reader.readPublishedSnapshot(CostSource.BOOKED))
                .thenReturn(mock(PracticeOperatingCostResponseDTO.class));

        assertThrows(ServiceUnavailableException.class,
                () -> provider(em, reader).getSnapshot(CostSource.BOOKED));
    }

    private static PracticeCostSnapshotProvider provider(
            EntityManager em, CxoPracticeOperatingCostService reader) {
        PracticeCostSnapshotProvider provider = new PracticeCostSnapshotProvider();
        provider.em = em;
        provider.snapshotReader = reader;
        return provider;
    }

    private static Object[] legacyPointer(String generation) {
        Object[] row = new Object[26];
        row[0] = true;
        row[1] = "READY";
        row[2] = null;
        row[3] = Timestamp.valueOf(generation);
        row[4] = Timestamp.valueOf("2026-01-03 00:00:00");
        row[5] = null;
        row[9] = 1L;
        row[10] = 1L;
        row[11] = 1L;
        return row;
    }
}
