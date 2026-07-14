package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeAttributionServiceTest {

    @Test
    void metadataReportsCoverageStartAndFallbackLimitation() {
        PracticeAttributionService.AttributionMetadata metadata =
                PracticeAttributionService.metadataFor(java.sql.Date.valueOf("2026-07-14"));

        assertEquals(UtilizationCalculationHelper.PRACTICE_ATTRIBUTION_METHOD, metadata.method());
        assertEquals(LocalDate.of(2026, 7, 14), metadata.coverageStartDate());
        assertTrue(metadata.note().contains("Earlier dates fall back"));
        assertTrue(metadata.note().contains("current user.practice"));
    }

    @Test
    void metadataKeepsCoverageNullBeforeSnapshotTableHasRows() {
        PracticeAttributionService.AttributionMetadata metadata =
                PracticeAttributionService.metadataFor(null);

        assertNull(metadata.coverageStartDate());
    }

    @Test
    void fallbackSqlUsesDatabaseDateAndIdempotentCurrentDayUpsert() {
        assertTrue(PracticeAttributionService.CLOSE_PRIOR_INTERVAL_SQL.contains("CURRENT_DATE"));
        assertTrue(PracticeAttributionService.DELETE_CURRENT_INTERVAL_SQL.contains("CURRENT_DATE"));
        assertTrue(PracticeAttributionService.UPSERT_CURRENT_INTERVAL_SQL.contains("CURRENT_DATE"));
        assertTrue(PracticeAttributionService.UPSERT_CURRENT_INTERVAL_SQL.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(PracticeAttributionService.UPSERT_CURRENT_INTERVAL_SQL.contains("effective_to = NULL"));
    }

    @Test
    void unchangedPracticeDoesNotWriteFallbackHistory() {
        EntityManager em = mock(EntityManager.class);
        PracticeAttributionService service = new PracticeAttributionService();
        service.em = em;

        service.recordUserCreated("user-1", null);
        service.recordPracticeChange("user-1", "PM", "PM");

        verify(em, never()).createNativeQuery(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void changedPracticeClosesPriorAndUpsertsCurrentInterval() {
        EntityManager em = mock(EntityManager.class);
        Query close = mock(Query.class);
        Query upsert = mock(Query.class);
        when(em.createNativeQuery(PracticeAttributionService.CLOSE_PRIOR_INTERVAL_SQL)).thenReturn(close);
        when(em.createNativeQuery(PracticeAttributionService.UPSERT_CURRENT_INTERVAL_SQL)).thenReturn(upsert);
        PracticeAttributionService service = new PracticeAttributionService();
        service.em = em;

        service.recordPracticeChange("user-1", "PM", "BA");

        verify(close).executeUpdate();
        verify(upsert).executeUpdate();
        verify(upsert).setParameter("practice", "BA");
    }

    @Test
    void clearingPracticeClosesPriorAndDeletesSameDayInterval() {
        EntityManager em = mock(EntityManager.class);
        Query close = mock(Query.class);
        Query delete = mock(Query.class);
        when(em.createNativeQuery(PracticeAttributionService.CLOSE_PRIOR_INTERVAL_SQL)).thenReturn(close);
        when(em.createNativeQuery(PracticeAttributionService.DELETE_CURRENT_INTERVAL_SQL)).thenReturn(delete);
        PracticeAttributionService service = new PracticeAttributionService();
        service.em = em;

        service.recordPracticeChange("user-1", "PM", null);

        verify(close).executeUpdate();
        verify(delete).executeUpdate();
        verify(em, never()).createNativeQuery(PracticeAttributionService.UPSERT_CURRENT_INTERVAL_SQL);
    }
}
