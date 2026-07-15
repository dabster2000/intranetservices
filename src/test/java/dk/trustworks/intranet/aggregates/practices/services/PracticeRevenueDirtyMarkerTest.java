package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.YearMonth;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeRevenueDirtyMarkerTest {
    @Test
    void onlyFrozenCostEvidenceRoutesCostFirst() {
        var actual = EnumSet.noneOf(PracticeRevenueDirtyMarker.Source.class);
        for (var source : PracticeRevenueDirtyMarker.Source.values()) {
            if (source.requiresCostFirst()) actual.add(source);
        }
        assertEquals(EnumSet.of(PracticeRevenueDirtyMarker.Source.FINANCE_GL,
                PracticeRevenueDirtyMarker.Source.ACCOUNT_CLASSIFICATION,
                PracticeRevenueDirtyMarker.Source.PRACTICE_BASIS_INPUT), actual);
    }

    @Test
    void runningRecoveryOwnerBlocksAnOrdinaryImportClaim(){
        EntityManager em=mock(EntityManager.class);
        Query query=query();
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(0);
        PracticeRevenueDirtyMarker marker=new PracticeRevenueDirtyMarker();
        marker.em=em;

        var failure=assertThrows(PracticeRevenueDirtyMarker.WatermarkConflictException.class,
                ()->marker.beginImport(PracticeRevenueDirtyMarker.Source.SELF_BILLED));

        assertEquals("SOURCE_IMPORT_ALREADY_RUNNING",failure.getMessage());
        verify(query).executeUpdate();
    }

    @Test
    void onlyOwningRecoveryTokenCanAdvanceReadyExactlyOnce(){
        EntityManager em=mock(EntityManager.class);
        Query query=query();
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);
        PracticeRevenueDirtyMarker marker=new PracticeRevenueDirtyMarker();
        marker.em=em;

        marker.completeRecovery(PracticeRevenueDirtyMarker.Source.PHANTOM_ATTRIBUTION,
                "11111111-1111-1111-1111-111111111111",YearMonth.parse("2021-07"),
                YearMonth.parse("2026-06"),null);

        verify(query).setParameter("source","PHANTOM_ATTRIBUTION");
        verify(query).setParameter("token","11111111-1111-1111-1111-111111111111");
        verify(query).executeUpdate();
    }

    @Test
    void deliveryCompletionRequiresTheOwnedObservedCursor(){
        EntityManager em=mock(EntityManager.class);
        Query query=query();
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(0);
        PracticeRevenueDirtyMarker marker=new PracticeRevenueDirtyMarker();
        marker.em=em;

        var failure=assertThrows(PracticeRevenueDirtyMarker.WatermarkConflictException.class,
                ()->marker.completeRecovery(PracticeRevenueDirtyMarker.Source.DELIVERY_EVIDENCE,
                        "11111111-1111-1111-1111-111111111111",YearMonth.parse("2021-07"),
                        YearMonth.parse("2026-06"),BigInteger.TEN));

        assertEquals("SOURCE_RECOVERY_OWNER_LOST",failure.getMessage());
    }

    private static Query query(){
        Query query=mock(Query.class);
        when(query.setParameter(anyString(),any())).thenReturn(query);
        return query;
    }
}
