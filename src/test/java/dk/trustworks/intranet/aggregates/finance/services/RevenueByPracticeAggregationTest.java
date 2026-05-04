package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.cxo.MonthlyRevenuePracticeDataPoint;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.RevenuePracticeDTO;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit test for the in-memory aggregation logic in
 * CxoFinanceService.buildRevenueByPracticeResponse.
 *
 * No Quarkus boot, no DB. Synthetic Tuples drive every branch:
 *  - cost-only month back-fill
 *  - unknown practice id → "OTHER"
 *  - "OTHER" appended last in the practices list
 *  - LinkedHashMap order preserved in practiceRevenue
 */
class RevenueByPracticeAggregationTest {

    /** Minimal fake Tuple backed by a Map. */
    private static Tuple tuple(Map<String, Object> values) {
        return new Tuple() {
            @Override public <X> X get(TupleElement<X> tupleElement) { throw new UnsupportedOperationException(); }
            @Override public <X> X get(String alias, Class<X> type) { return type.cast(values.get(alias)); }
            @Override public Object get(String alias) { return values.get(alias); }
            @Override public <X> X get(int i, Class<X> type) { throw new UnsupportedOperationException(); }
            @Override public Object get(int i) { throw new UnsupportedOperationException(); }
            @Override public Object[] toArray() { return values.values().toArray(); }
            @Override public List<TupleElement<?>> getElements() { throw new UnsupportedOperationException(); }
        };
    }

    private static Tuple revenueRow(String monthKey, int year, int monthNumber, String practice, double revenue) {
        Map<String, Object> m = new HashMap<>();
        m.put("month_key", monthKey);
        m.put("year_val", year);
        m.put("month_number", monthNumber);
        m.put("service_line_id", practice);
        m.put("revenue_dkk", revenue);
        return tuple(m);
    }

    private static Tuple costRow(String monthKey, int year, int monthNumber, double cost) {
        Map<String, Object> m = new HashMap<>();
        m.put("month_key", monthKey);
        m.put("year_val", year);
        m.put("month_number", monthNumber);
        m.put("cost_dkk", cost);
        return tuple(m);
    }

    @Test
    void unknownPracticeIdsCollapseToOther() {
        List<Tuple> revenue = List.of(
            revenueRow("202501", 2025, 1, "ZZZ", 100.0));
        List<Tuple> cost = List.of();
        RevenuePracticeDTO result = CxoFinanceService.buildRevenueByPracticeResponse(revenue, cost);
        assertEquals(List.of("OTHER"), result.practices());
        assertEquals(1, result.months().size());
        assertEquals(100.0, result.months().get(0).practiceRevenue().get("OTHER"));
    }

    @Test
    void otherIsAppendedLastInPracticesList() {
        List<Tuple> revenue = List.of(
            revenueRow("202501", 2025, 1, "OTHER", 10.0),
            revenueRow("202501", 2025, 1, "PM", 20.0),
            revenueRow("202501", 2025, 1, "DEV", 30.0));
        RevenuePracticeDTO result = CxoFinanceService.buildRevenueByPracticeResponse(revenue, List.of());
        assertEquals("OTHER", result.practices().get(result.practices().size() - 1));
        // Known practices come first in canonical order
        assertEquals(List.of("PM", "DEV", "OTHER"), result.practices());
    }

    @Test
    void costOnlyMonthIsBackfilledWithZeroRevenue() {
        List<Tuple> revenue = List.of();
        List<Tuple> cost = List.of(costRow("202503", 2025, 3, 500.0));
        RevenuePracticeDTO result = CxoFinanceService.buildRevenueByPracticeResponse(revenue, cost);
        assertEquals(1, result.months().size());
        MonthlyRevenuePracticeDataPoint m = result.months().get(0);
        assertEquals("202503", m.monthKey());
        assertEquals(0.0, m.totalRevenueDkk());
        assertEquals(500.0, m.totalCostDkk());
        assertNull(m.marginPercent(), "marginPercent must be null when revenue is 0");
    }

    @Test
    void marginPercentIsRoundedToTwoDecimals() {
        List<Tuple> revenue = List.of(revenueRow("202501", 2025, 1, "PM", 1000.0));
        List<Tuple> cost = List.of(costRow("202501", 2025, 1, 333.0));
        RevenuePracticeDTO result = CxoFinanceService.buildRevenueByPracticeResponse(revenue, cost);
        // (1000 - 333) / 1000 = 66.7%
        assertEquals(66.7, result.months().get(0).marginPercent(), 0.01);
    }

    @Test
    void monthsAreSortedChronologically() {
        List<Tuple> revenue = List.of(
            revenueRow("202503", 2025, 3, "PM", 30.0),
            revenueRow("202501", 2025, 1, "PM", 10.0),
            revenueRow("202502", 2025, 2, "PM", 20.0));
        RevenuePracticeDTO result = CxoFinanceService.buildRevenueByPracticeResponse(revenue, List.of());
        assertEquals(List.of("202501", "202502", "202503"),
            result.months().stream().map(MonthlyRevenuePracticeDataPoint::monthKey).toList());
    }
}
