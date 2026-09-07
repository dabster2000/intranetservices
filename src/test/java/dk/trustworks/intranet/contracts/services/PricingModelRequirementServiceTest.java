package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.services.PricingModelRequirementService.SalaryPeriod;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PricingModelRequirementServiceTest {
    private static final LocalDate START = LocalDate.of(2020, 1, 1);
    private static final LocalDate END = START.plusMonths(1);

    @Test
    void hourlyPeriodEndingAtAssignmentStartDoesNotOverlap() {
        assertFalse(required(List.of(salary(START.minusYears(1), SalaryType.HOURLY),
                salary(START, SalaryType.NORMAL))));
    }

    @Test
    void hourlyPeriodOnOnlyFirstOrLastAssignmentDayStillRequiresModel() {
        assertTrue(required(List.of(salary(START.minusYears(1), SalaryType.HOURLY),
                salary(START.plusDays(1), SalaryType.NORMAL))));
        assertTrue(required(List.of(salary(START.minusYears(1), SalaryType.NORMAL),
                salary(END, SalaryType.HOURLY))));
    }

    @Test
    void hourlyPeriodEntirelyInsideHistoricalAssignmentRequiresModel() {
        assertTrue(required(List.of(salary(START, SalaryType.NORMAL),
                salary(START.plusDays(5), SalaryType.HOURLY),
                salary(START.plusDays(10), SalaryType.NORMAL))));
    }

    @Test
    void hourlySalaryAfterAssignmentAndMissingSalaryAreOptional() {
        assertFalse(required(List.of(salary(END.plusDays(1), SalaryType.HOURLY))));
        assertFalse(required(List.of()));
        assertFalse(required(List.of(salary(START, null))));
        assertFalse(required(List.of(salary(START.minusYears(1), SalaryType.NORMAL))));
    }

    @Test
    void oneDayAssignmentUsesSalaryEffectiveThatDay() {
        assertTrue(PricingModelRequirementService.overlapsHourly(
                List.of(salary(START, SalaryType.HOURLY)), START, START));
        assertFalse(PricingModelRequirementService.overlapsHourly(
                List.of(salary(START.minusDays(1), SalaryType.HOURLY), salary(START, SalaryType.NORMAL)), START, START));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lookupBindsUserAndEndDateAndPropagatesFailure() {
        PricingModelRequirementService service = new PricingModelRequirementService();
        service.em = mock(EntityManager.class);
        TypedQuery<Object[]> query = mock(TypedQuery.class);
        when(service.em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.<Object[]>of(new Object[]{START, SalaryType.HOURLY}));
        assertTrue(service.isRequired("consultant", START, END));
        verify(query).setParameter("userUuid", "consultant");
        verify(query).setParameter("endDate", END);
        when(query.getResultList()).thenThrow(new PersistenceException("offline"));
        assertThrows(PersistenceException.class, () -> service.isRequired("consultant", START, END));
    }

    @Test
    void invalidIdentityOrRangeNeverQueriesSalary() {
        PricingModelRequirementService service = new PricingModelRequirementService();
        service.em = mock(EntityManager.class);
        assertThrows(BadRequestException.class, () -> service.isRequired(null, START, END));
        assertThrows(BadRequestException.class, () -> service.isRequired(" ", START, END));
        assertThrows(BadRequestException.class, () -> service.isRequired("consultant", null, END));
        assertThrows(BadRequestException.class, () -> service.isRequired("consultant", END, START));
        verifyNoInteractions(service.em);
    }

    private static boolean required(List<SalaryPeriod> periods) {
        return PricingModelRequirementService.overlapsHourly(periods, START, END);
    }

    private static SalaryPeriod salary(LocalDate from, SalaryType type) {
        return new SalaryPeriod(from, type);
    }
}
