package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PracticeCostSnapshotProviderTest {
    @Test
    void disabledLegacyGateDoesNotBlockCanonicalBuildReads() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        PracticeCostSnapshotLoader loader = mock(PracticeCostSnapshotLoader.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        Object[] disabled = legacyPointer("2026-01-01 00:00:00");
        disabled[0] = false;
        when(query.getResultList()).thenReturn(Collections.singletonList(disabled));
        PracticeCostSnapshotProvider.CanonicalSnapshot canonical =
                mock(PracticeCostSnapshotProvider.CanonicalSnapshot.class);
        when(loader.readPublishedSnapshot(eq(CostSource.BOOKED), any(IntSupplier.class)))
                .thenReturn(canonical);
        PracticeCostSnapshotProvider provider = provider(em, loader);

        var snapshot = provider.getSnapshot(CostSource.BOOKED);

        assertFalse(snapshot.servingEnabled());
        assertSame(canonical, snapshot.canonical());
        verify(loader).readPublishedSnapshot(eq(CostSource.BOOKED), any(IntSupplier.class));
    }

    @Test
    void disabledIntegrityGatePreservesLegacyShapeButWithholdsEveryFinancialMetric() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        PracticeCostSnapshotLoader loader = mock(PracticeCostSnapshotLoader.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        Object[] disabled = legacyPointer("2026-01-01 00:00:00");
        disabled[0] = false;
        when(query.getResultList()).thenReturn(Collections.singletonList(disabled));
        when(loader.readPublishedSnapshot(eq(CostSource.BOOKED), any(IntSupplier.class)))
                .thenReturn(mock(PracticeCostSnapshotProvider.CanonicalSnapshot.class));

        PracticeOperatingCostResponseDTO response = provider(em, loader)
                .getLegacySnapshot(CostSource.BOOKED).response();

        assertEquals(PracticeCostSnapshotLoader.PRACTICES.size(), response.practices().size());
        assertEquals("UNAVAILABLE", response.completenessStatus());
        response.practices().forEach(row -> {
            assertNull(row.currentSalaryDkk());
            assertNull(row.currentOpexDkk());
            assertNull(row.currentTotalDkk());
            assertNull(row.currentAverageFte());
            assertNull(row.currentCostPerFteDkk());
        });
        verify(loader, never()).readPublishedSnapshot(any(), any(IntSupplier.class));
    }

    @Test
    void enabledLegacyGateReturnsTheSameCertifiedCanonicalSnapshot() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        PracticeCostSnapshotLoader loader = mock(PracticeCostSnapshotLoader.class);
        Object[] pointer = legacyPointer("2026-01-01 00:00:00");
        PracticeCostSnapshotProvider.CanonicalSnapshot canonical =
                mock(PracticeCostSnapshotProvider.CanonicalSnapshot.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(
                Collections.singletonList(Boolean.TRUE),
                Collections.singletonList(pointer),
                Collections.singletonList(pointer));
        when(loader.readPublishedSnapshot(eq(CostSource.BOOKED), any(IntSupplier.class)))
                .thenReturn(canonical);

        var snapshot = provider(em, loader).getLegacySnapshot(CostSource.BOOKED);

        assertSame(canonical, snapshot.canonical());
        verify(loader).readPublishedSnapshot(eq(CostSource.BOOKED), any(IntSupplier.class));
    }

    @Test
    void publicationChangeDuringLegacyBootstrapReadFailsClosed() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        PracticeCostSnapshotLoader loader = mock(PracticeCostSnapshotLoader.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        Object[] before = legacyPointer("2026-01-01 00:00:00");
        Object[] after = legacyPointer("2026-01-02 00:00:00");
        when(query.getResultList()).thenReturn(
                Collections.singletonList(before), Collections.singletonList(after));
        when(loader.readPublishedSnapshot(eq(CostSource.BOOKED), any(IntSupplier.class)))
                .thenReturn(mock(PracticeCostSnapshotProvider.CanonicalSnapshot.class));

        assertThrows(ServiceUnavailableException.class,
                () -> provider(em, loader).getSnapshot(CostSource.BOOKED));
    }

    @Test
    void contributionBoundPropagatesToBothPointersAndTheCanonicalLoader() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        PracticeCostSnapshotLoader loader = mock(PracticeCostSnapshotLoader.class);
        PracticeCostSnapshotProvider.CanonicalSnapshot canonical =
                mock(PracticeCostSnapshotProvider.CanonicalSnapshot.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.singletonList(
                legacyPointer("2026-01-01 00:00:00")));
        when(loader.readPublishedSnapshot(eq(CostSource.BOOKED), any(IntSupplier.class)))
                .thenReturn(canonical);

        var snapshot = provider(em, loader).getSnapshot(CostSource.BOOKED, 3_210);

        assertSame(canonical, snapshot.canonical());
        verify(query, times(2)).setHint("jakarta.persistence.query.timeout", 3_210);
        verify(loader).readPublishedSnapshot(eq(CostSource.BOOKED), any(IntSupplier.class));
    }

    @Test
    void contributionDeadlineIsResampledBeforePointersAndEveryLoaderQuery() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        PracticeCostSnapshotLoader loader = mock(PracticeCostSnapshotLoader.class);
        PracticeCostSnapshotProvider.CanonicalSnapshot canonical =
                mock(PracticeCostSnapshotProvider.CanonicalSnapshot.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.singletonList(
                legacyPointer("2026-01-01 00:00:00")));
        when(loader.readPublishedSnapshot(eq(CostSource.BOOKED), any(IntSupplier.class)))
                .thenAnswer(invocation -> {
                    IntSupplier remaining = invocation.getArgument(1);
                    assertEquals(3_000, remaining.getAsInt());
                    return canonical;
                });

        var snapshot = provider(em, loader).getSnapshot(
                CostSource.BOOKED, sequencedTimeouts(4_000, 3_000, 2_000));

        assertSame(canonical, snapshot.canonical());
        verify(query).setHint("jakarta.persistence.query.timeout", 4_000);
        verify(query).setHint("jakarta.persistence.query.timeout", 2_000);
    }

    @Test
    void contributionBoundRejectsNonPositiveAndLegacyWideningValuesBeforeSql() {
        EntityManager em = mock(EntityManager.class);
        PracticeCostSnapshotLoader loader = mock(PracticeCostSnapshotLoader.class);
        PracticeCostSnapshotProvider provider = provider(em, loader);

        assertThrows(IllegalArgumentException.class,
                () -> provider.getSnapshot(CostSource.BOOKED, 0));
        assertThrows(IllegalArgumentException.class,
                () -> provider.getSnapshot(CostSource.BOOKED, 15_001));

        verifyNoInteractions(em, loader);
    }

    private static PracticeCostSnapshotProvider provider(
            EntityManager em, PracticeCostSnapshotLoader loader) {
        PracticeCostSnapshotProvider provider = new PracticeCostSnapshotProvider();
        provider.em = em;
        provider.snapshotLoader = loader;
        return provider;
    }

    private static Object[] legacyPointer(String generation) {
        Object[] row = new Object[41];
        row[0] = true;
        row[1] = "READY";
        row[2] = null;
        row[3] = Timestamp.valueOf(generation);
        row[4] = Timestamp.valueOf("2026-01-03 00:00:00");
        row[5] = null;
        row[9] = 1L;
        row[10] = 1L;
        row[11] = 1L;
        row[40] = 1L;
        return row;
    }

    private static IntSupplier sequencedTimeouts(int... values) {
        List<Integer> remaining = new ArrayList<>();
        for (int value : values) remaining.add(value);
        return remaining::removeFirst;
    }
}
