package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for the run-rate forecast helpers — no Quarkus boot / DB required (the extracted math is
 * pure). Covers the P2#8 fix: a mid-FY projection of a production basis is annualised over the elapsed
 * employed months so it reflects a year-end estimate rather than production-to-date.
 */
class IndividualBonusBasisResolverTest {

    // --- runRate: annualise actuals over elapsed employed months ---

    @Test
    void runRate_midWindow_annualisesToFullWindow() {
        // 1,200,000 booked over 4 elapsed employed months of a 10-month window → 3,000,000 estimate.
        BigDecimal forecast = IndividualBonusBasisResolver.runRate(bd(1_200_000), 4, 10);
        assertEquals(0, forecast.compareTo(bd(3_000_000)), "run-rate of 1.2M/4×10 should be 3.0M but was " + forecast);
    }

    @Test
    void runRate_fullyElapsedWindow_returnsActuals() {
        // total <= elapsed → nothing to extrapolate; return the booked actuals unchanged.
        assertEquals(0, IndividualBonusBasisResolver.runRate(bd(675_000), 12, 12).compareTo(bd(675_000)));
        assertEquals(0, IndividualBonusBasisResolver.runRate(bd(675_000), 12, 10).compareTo(bd(675_000)));
    }

    @Test
    void runRate_nothingElapsed_returnsActuals() {
        // elapsed <= 0 → cannot annualise (would divide by zero); degrade to actuals (typically ~0).
        assertEquals(0, IndividualBonusBasisResolver.runRate(BigDecimal.ZERO, 0, 12).compareTo(BigDecimal.ZERO));
        assertEquals(0, IndividualBonusBasisResolver.runRate(bd(50_000), 0, 12).compareTo(bd(50_000)));
    }

    @Test
    void runRate_nullActuals_isZero() {
        assertEquals(0, IndividualBonusBasisResolver.runRate(null, 4, 10).compareTo(BigDecimal.ZERO));
    }

    @Test
    void runRate_roundsToOere() {
        // 100 over 3 elapsed × 10 total = 333.333... → 333.33 (2dp, HALF_UP).
        BigDecimal forecast = IndividualBonusBasisResolver.runRate(bd(100), 3, 10);
        assertEquals(0, forecast.compareTo(new BigDecimal("333.33")), "expected 333.33 but was " + forecast);
    }

    // --- isForecastable: only ADDITIVE production/hours sums are scaled ---

    @Test
    void isForecastable_additiveBases_true() {
        assertTrue(IndividualBonusBasisResolver.isForecastable(Basis.OWN_INVOICED_REVENUE));
        assertTrue(IndividualBonusBasisResolver.isForecastable(Basis.REGISTERED_BILLABLE_VALUE));
        assertTrue(IndividualBonusBasisResolver.isForecastable(Basis.BILLABLE_HOURS));
    }

    @Test
    void isForecastable_ratioAndLevelBases_false() {
        // Ratios/levels are already period-normalised — scaling by month count would inflate them.
        assertFalse(IndividualBonusBasisResolver.isForecastable(Basis.UTILIZATION));
        assertFalse(IndividualBonusBasisResolver.isForecastable(Basis.BUDGET_ATTAINMENT));
        assertFalse(IndividualBonusBasisResolver.isForecastable(Basis.SALARY));
        assertFalse(IndividualBonusBasisResolver.isForecastable(Basis.FIXED_AMOUNT));
    }

    // --- monthsBetweenInclusive: distinct calendar months spanned ---

    @Test
    void monthsBetweenInclusive_countsBothEndpoints() {
        assertEquals(1, IndividualBonusBasisResolver.monthsBetweenInclusive(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
        assertEquals(10, IndividualBonusBasisResolver.monthsBetweenInclusive(
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 6, 30)));
        assertEquals(12, IndividualBonusBasisResolver.monthsBetweenInclusive(
                LocalDate.of(2026, 7, 1), LocalDate.of(2027, 6, 30)));
    }

    @Test
    void registeredBillableValue_sumsRegisteredAmountForActiveUserAcrossInclusiveWindow() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(new BigDecimal("1250000.00"));
        IndividualBonusBasisResolver resolver = new IndividualBonusBasisResolver();
        resolver.em = em;
        LocalDate from = LocalDate.of(2023, 7, 1);
        LocalDate to = LocalDate.of(2024, 6, 30);

        BigDecimal amount = resolver.resolveBasisAmount(
                Basis.REGISTERED_BILLABLE_VALUE, "employee-uuid", from, to);

        assertEquals(0, amount.compareTo(new BigDecimal("1250000.00")));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("SUM(fud.registered_amount)"));
        assertTrue(sql.getValue().contains("fud.status_type = 'ACTIVE'"));
        assertTrue(sql.getValue().contains("fud.document_date >= :from"));
        assertTrue(sql.getValue().contains("fud.document_date <= :to"));
        assertFalse(sql.getValue().toLowerCase().contains("discount"));
        verify(query).setParameter("userUuid", "employee-uuid");
        verify(query).setParameter("from", from);
        verify(query).setParameter("to", to);
    }

    @Test
    void billableHoursFormulaVariable_remainsRawHours() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(new BigDecimal("1250.00"));
        IndividualBonusBasisResolver resolver = new IndividualBonusBasisResolver();
        resolver.em = em;
        LocalDate from = LocalDate.of(2023, 7, 1);
        LocalDate to = LocalDate.of(2024, 6, 30);

        BigDecimal hours = resolver.resolveVariable("billableHours", "employee-uuid", from, to);

        assertEquals(0, hours.compareTo(new BigDecimal("1250.00")));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("SUM(fud.registered_billable_hours)"));
        assertFalse(sql.getValue().contains("SUM(fud.registered_amount)"));
    }

    @Test
    void compositeUtilizationUsesOneActiveInclusiveQueryAndReturnsCoverage() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        LocalDateTime watermark = LocalDateTime.of(2026, 8, 1, 2, 3, 4);
        when(query.getSingleResult()).thenReturn(new Object[]{
                new BigDecimal("93.0000"), new BigDecimal("100.0000"),
                31L, 31L, BigDecimal.ZERO, Timestamp.valueOf(watermark)
        });
        IndividualBonusBasisResolver resolver = new IndividualBonusBasisResolver();
        resolver.em = em;
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);

        var result = resolver.resolveUtilization("employee-uuid", from, to);

        assertEquals(new BigDecimal("0.930000"), result.rawUtilization());
        assertEquals(0, result.billableHours().compareTo(new BigDecimal("93.0000")));
        assertEquals(31, result.coverage().expectedRows());
        assertEquals(31, result.coverage().actualRows());
        assertEquals(0, result.coverage().duplicateRows());
        assertEquals(watermark, result.coverage().factsAsOf());
        assertTrue(result.coverage().complete());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("fud.status_type = 'ACTIVE'"));
        assertTrue(sql.getValue().contains("SUM(fud.registered_billable_hours)"));
        assertTrue(sql.getValue().contains("SUM(fud.net_available_hours)"));
        assertTrue(sql.getValue().contains("COUNT(DISTINCT fud.document_date)"));
        assertTrue(sql.getValue().contains("MAX(fud.last_update)"));
        verify(query).setParameter("userUuid", "employee-uuid");
        verify(query).setParameter("from0", from);
        verify(query).setParameter("to0", to);
    }

    @Test
    void compositeUtilizationZeroDenominatorHasExactSixDecimalZero() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(new Object[]{
                new BigDecimal("5.0000"), BigDecimal.ZERO,
                1L, 1L, BigDecimal.ZERO, Timestamp.valueOf(LocalDateTime.of(2026, 8, 1, 0, 0))
        });
        IndividualBonusBasisResolver resolver = new IndividualBonusBasisResolver();
        resolver.em = em;

        var result = resolver.resolveUtilization("employee-uuid",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1));

        assertEquals(new BigDecimal("0.000000"), result.rawUtilization());
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }
}
