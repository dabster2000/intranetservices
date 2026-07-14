package dk.trustworks.intranet.aggregates.utilization.services;

import dk.trustworks.intranet.aggregates.utilization.dto.ActualDataStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FactUserDayFreshnessServiceTest {

    @Test
    void completeAndLaggedCutoffs_areMeasuredAgainstCopenhagenYesterday() {
        Instant refreshed = Instant.parse("2026-07-14T03:00:00Z");

        var complete = FactUserDayFreshnessService.evaluate(
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 13),
                refreshed, "READY");
        assertEquals(LocalDate.of(2026, 7, 13), complete.requestedActualThroughDate());
        assertEquals(LocalDate.of(2026, 7, 13), complete.actualDataThroughDate());
        assertEquals(ActualDataStatus.COMPLETE, complete.actualDataStatus());
        assertEquals(0, complete.actualSourceLagDays());

        var lagged = FactUserDayFreshnessService.evaluate(
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 12),
                refreshed, "READY");
        assertEquals(LocalDate.of(2026, 7, 12), lagged.actualDataThroughDate());
        assertEquals(ActualDataStatus.SOURCE_LAGGED, lagged.actualDataStatus());
        assertEquals(1, lagged.actualSourceLagDays());
    }

    @Test
    void missingRunningAndFailedWatermarks_areUnavailable() {
        for (String state : new String[]{null, "UNINITIALIZED", "RUNNING", "FAILED"}) {
            var freshness = FactUserDayFreshnessService.evaluate(
                    LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 13),
                    Instant.parse("2026-07-14T03:00:00Z"), state);
            assertEquals(ActualDataStatus.UNAVAILABLE, freshness.actualDataStatus());
            assertNull(freshness.actualDataThroughDate());
            assertNull(freshness.actualSourceLagDays());
        }

        var missing = FactUserDayFreshnessService.evaluate(
                LocalDate.of(2026, 7, 14), null, null, "READY");
        assertEquals(ActualDataStatus.UNAVAILABLE, missing.actualDataStatus());
    }

    @Test
    void futureWatermark_isCappedAtRequestedCutoff() {
        var freshness = FactUserDayFreshnessService.evaluate(
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 20),
                Instant.parse("2026-07-14T03:00:00Z"), "READY");

        assertEquals(LocalDate.of(2026, 7, 13), freshness.actualDataThroughDate());
        assertEquals(ActualDataStatus.COMPLETE, freshness.actualDataStatus());
        assertEquals(0, freshness.actualSourceLagDays());
    }

    @Test
    void firstDayAndTimezoneBoundary_useCopenhagenCalendar() {
        assertEquals(LocalDate.of(2026, 3, 30), FactUserDayFreshnessService.reportingDate(
                Instant.parse("2026-03-29T22:30:00Z")));

        var freshness = FactUserDayFreshnessService.evaluate(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 7, 31),
                Instant.parse("2026-08-01T03:00:00Z"), "READY");
        assertEquals(LocalDate.of(2026, 7, 31), freshness.requestedActualThroughDate());
        assertEquals(ActualDataStatus.COMPLETE, freshness.actualDataStatus());
    }

    @Test
    void databaseRow_usesLatestSuccessfulRefreshTimestampAsUtc() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(FactUserDayFreshnessService.WATERMARK_SQL)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.<Object[]>of(new Object[]{
                Date.valueOf("2026-07-12"),
                Timestamp.valueOf(LocalDateTime.of(2026, 7, 13, 3, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 7, 13, 19, 40, 5)),
                "READY"
        }));

        FactUserDayFreshnessService service = new FactUserDayFreshnessService();
        service.em = em;
        var freshness = service.resolve(LocalDate.of(2026, 7, 14));

        assertEquals(Instant.parse("2026-07-13T19:40:05Z"), freshness.sourceRefreshedAt());
        assertEquals(ActualDataStatus.SOURCE_LAGGED, freshness.actualDataStatus());
        verify(query).setParameter("pipelineName", "FACT_USER_DAY");
        verify(query).setHint("jakarta.persistence.query.timeout", 15_000);
    }

    @Test
    void missingDatabaseRow_isUnavailable() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(FactUserDayFreshnessService.WATERMARK_SQL)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        FactUserDayFreshnessService service = new FactUserDayFreshnessService();
        service.em = em;
        var freshness = service.resolve(LocalDate.of(2026, 7, 14));

        assertEquals(ActualDataStatus.UNAVAILABLE, freshness.actualDataStatus());
        assertEquals(LocalDate.of(2026, 7, 13), freshness.requestedActualThroughDate());
        assertNull(freshness.sourceRefreshedAt());
    }
}
