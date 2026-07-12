package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.HeadcountCompositionPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.StatusTrendPoint;
import dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsRepository;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterParams;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterRequest;
import jakarta.persistence.Tuple;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutivePeopleWorkforceServiceTest {

    @Test
    void compositionExposesInternalTotalAndKeepsExternalSeparate() {
        PeopleAnalyticsRepository repository = mock(PeopleAnalyticsRepository.class);
        Tuple row = datedRow();
        when(row.get("consultant")).thenReturn(114L);
        when(row.get("staff")).thenReturn(11L);
        when(row.get("student")).thenReturn(17L);
        when(row.get("external")).thenReturn(5L);
        when(row.get("employee_total")).thenReturn(142L);
        when(row.get("contracted_fte")).thenReturn(130.41d);
        when(repository.tuples(eq("headcount-composition"), anyString(), anyMap()))
                .thenReturn(List.of(row));
        ExecutivePeopleWorkforceService service = new ExecutivePeopleWorkforceService();
        service.repository = repository;

        HeadcountCompositionPoint point = service.headcountComposition(filters()).data().getFirst();

        assertEquals(142L, point.employeeTotal());
        assertEquals(point.consultant() + point.staff() + point.student(), point.employeeTotal());
        assertEquals(5L, point.external());
    }

    @Test
    void smallLeaveCellIsShownWhenFloorDisabled() {
        PeopleAnalyticsRepository repository = mock(PeopleAnalyticsRepository.class);
        Tuple row = datedRow();
        when(row.get("active")).thenReturn(100L);
        when(row.get("paid_leave")).thenReturn(1L);
        when(row.get("maternity_leave")).thenReturn(0L);
        when(row.get("non_pay_leave")).thenReturn(0L);
        when(row.get("employee_total")).thenReturn(101L);
        when(repository.tuples(eq("status-trend"), anyString(), anyMap())).thenReturn(List.of(row));
        ExecutivePeopleWorkforceService service = new ExecutivePeopleWorkforceService();
        service.repository = repository;

        StatusTrendPoint point = service.statusTrend(filters()).data().getFirst();

        assertFalse(point.suppressed());
        assertNull(point.suppressionReason());
        assertEquals(100L, point.active());
        assertEquals(1L, point.onLeave());
        assertEquals(101L, point.employeeTotal());
    }

    @Test
    void upcomingCellsAreDetailAvailableWhenFloorDisabled() {
        PeopleAnalyticsRepository repository = mock(PeopleAnalyticsRepository.class);
        Tuple hidden = upcomingRow("2026-08-01", "FIRST_HIRE", 1L);
        Tuple visible = upcomingRow("2026-08-15", "DEPARTURE", 3L);
        when(repository.tuples(eq("upcoming-changes"), anyString(), anyMap()))
                .thenReturn(List.of(hidden, visible));
        ExecutivePeopleWorkforceService service = new ExecutivePeopleWorkforceService();
        service.repository = repository;

        var summaries = service.upcomingChanges(filters()).data().summary();

        assertTrue(summaries.get(0).detailAvailable());
        assertNull(summaries.get(0).detailUnavailableReason());
        assertTrue(summaries.get(1).detailAvailable());
        assertNull(summaries.get(1).detailUnavailableReason());
    }

    private static PeopleFilterParams filters() {
        PeopleFilterRequest request = new PeopleFilterRequest();
        request.asOfDate = "2026-07-10";
        request.months = "24";
        request.horizonDays = "90";
        return PeopleFilterParams.from(request);
    }

    private static Tuple datedRow() {
        Tuple row = mock(Tuple.class);
        when(row.get("snapshot_date")).thenReturn(Date.valueOf(LocalDate.of(2026, 7, 10)));
        return row;
    }

    private static Tuple upcomingRow(String date, String type, long count) {
        Tuple row = mock(Tuple.class);
        when(row.get("effective_date")).thenReturn(Date.valueOf(date));
        when(row.get("event_type", String.class)).thenReturn(type);
        when(row.get("people_count")).thenReturn(count);
        return row;
    }
}
