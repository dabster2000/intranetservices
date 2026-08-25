package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryRevenueTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryTrendClientDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryTrendPointDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryTrendQuarterDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryTrendSegmentDTO;
import dk.trustworks.intranet.aggregates.finance.services.IndustryRevenueTrendService.BudgetRow;
import dk.trustworks.intranet.aggregates.finance.services.IndustryRevenueTrendService.PipelineRow;
import dk.trustworks.intranet.aggregates.finance.services.IndustryRevenueTrendService.RevenueRow;
import dk.trustworks.intranet.aggregates.finance.services.IndustryRevenueTrendService.TrendWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for the pure window/assembly logic behind
 * GET /clients/cxo/industry-revenue-trend.
 */
class IndustryRevenueTrendServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 25);

    // -------------------------------------------------------------------------
    // Window
    // -------------------------------------------------------------------------

    @Test
    void windowSpansTwelveActualAndThreeForecastQuarters() {
        TrendWindow window = TrendWindow.of(AS_OF);

        assertEquals(LocalDate.of(2023, 7, 1), window.actualStart());
        assertEquals(LocalDate.of(2026, 7, 1), window.currentQuarterStart());
        assertEquals(LocalDate.of(2027, 4, 1), window.forecastEndExclusive());
        assertEquals("202307", window.actualStartMonthKey());
        assertEquals("202703", window.forecastEndMonthKey());
        assertEquals("2026-Q3", window.currentQuarterKey());
        assertEquals("2026-Q2", window.latestFullQuarterKey());

        List<IndustryTrendQuarterDTO> quarters = window.quarterMetas();
        assertEquals(15, quarters.size());
        assertEquals("2023-Q3", quarters.get(0).quarterKey());
        assertEquals(IndustryTrendQuarterDTO.PHASE_ACTUAL, quarters.get(0).phase());
        assertEquals(IndustryTrendQuarterDTO.PHASE_ACTUAL, quarters.get(11).phase());
        assertEquals("2026-Q2", quarters.get(11).quarterKey());
        assertEquals(IndustryTrendQuarterDTO.PHASE_FORECAST, quarters.get(12).phase());
        assertEquals("2026-Q3", quarters.get(12).quarterKey());
        assertEquals("2027-Q1", quarters.get(14).quarterKey());
    }

    @Test
    void julyQuartersCarryFiscalYearLabels() {
        List<IndustryTrendQuarterDTO> quarters = TrendWindow.of(AS_OF).quarterMetas();

        assertEquals("FY23/24", quarters.get(0).fiscalYearLabel()); // 2023-Q3
        assertNull(quarters.get(1).fiscalYearLabel());              // 2023-Q4
        assertEquals("FY24/25", quarters.get(4).fiscalYearLabel()); // 2024-Q3
        assertEquals("FY25/26", quarters.get(8).fiscalYearLabel()); // 2025-Q3
        assertEquals("FY26/27", quarters.get(12).fiscalYearLabel()); // 2026-Q3
    }

    @Test
    void windowAtQuarterBoundaryStartsANewCurrentQuarter() {
        TrendWindow window = TrendWindow.of(LocalDate.of(2026, 10, 1));

        assertEquals("2026-Q4", window.currentQuarterKey());
        assertEquals("2026-Q3", window.latestFullQuarterKey());
        assertEquals(LocalDate.of(2023, 10, 1), window.actualStart());
    }

    @Test
    void quarterKeyForMonthKeyMapsAllMonths() {
        assertEquals("2026-Q1", IndustryRevenueTrendService.quarterKeyForMonthKey("202603"));
        assertEquals("2026-Q2", IndustryRevenueTrendService.quarterKeyForMonthKey("202604"));
        assertEquals("2026-Q4", IndustryRevenueTrendService.quarterKeyForMonthKey("202612"));
    }

    @Test
    void unknownSegmentsNormalizeToOther() {
        assertEquals("OTHER", IndustryRevenueTrendService.normalizeSegment(null));
        assertEquals("OTHER", IndustryRevenueTrendService.normalizeSegment(""));
        assertEquals("OTHER", IndustryRevenueTrendService.normalizeSegment("C25_HEALTH"));
        assertEquals("HEALTH", IndustryRevenueTrendService.normalizeSegment("HEALTH"));
    }

    // -------------------------------------------------------------------------
    // Assembly
    // -------------------------------------------------------------------------

    private static IndustryRevenueTrendDTO assemble(List<RevenueRow> revenue,
                                                    List<BudgetRow> budget,
                                                    List<PipelineRow> pipeline,
                                                    Map<String, String> firstInvoice) {
        return IndustryRevenueTrendService.assemble(
                TrendWindow.of(AS_OF), revenue, budget, pipeline, firstInvoice);
    }

    private static IndustryTrendSegmentDTO segment(IndustryRevenueTrendDTO dto, String code) {
        return dto.industries().stream()
                .filter(s -> s.segmentCode().equals(code))
                .findFirst().orElseThrow();
    }

    private static IndustryTrendPointDTO point(IndustryTrendSegmentDTO segment, String quarterKey) {
        return segment.series().stream()
                .filter(p -> p.quarterKey().equals(quarterKey))
                .findFirst().orElseThrow();
    }

    @Test
    void allSixSegmentsAlwaysPresentWithFullSeries() {
        IndustryRevenueTrendDTO dto = assemble(List.of(), List.of(), List.of(), Map.of());

        assertEquals(IndustryRevenueTrendService.SEGMENT_ORDER,
                dto.industries().stream().map(IndustryTrendSegmentDTO::segmentCode).toList());
        for (IndustryTrendSegmentDTO segment : dto.industries()) {
            assertEquals(15, segment.series().size());
            assertTrue(segment.clients().isEmpty());
        }
        assertEquals("2026-Q2", dto.latestFullQuarterKey());
        assertEquals("2026-Q3", dto.currentQuarterKey());
        assertEquals("Energy & Utilities", segment(dto, "ENERGY").displayName());
    }

    @Test
    void revenueAggregatesMonthsIntoQuartersAndSplitsCurrentQuarterToDate() {
        List<RevenueRow> revenue = List.of(
                new RevenueRow("PUBLIC", "c1", "Client One", "202504", 100_000d),
                new RevenueRow("PUBLIC", "c1", "Client One", "202505", 50_000d),
                new RevenueRow("PUBLIC", "c1", "Client One", "202607", 30_000d), // current quarter → to-date
                new RevenueRow("PUBLIC", "c2", "Client Two", "202504", 25_000d));

        IndustryRevenueTrendDTO dto = assemble(revenue, List.of(), List.of(), Map.of());
        IndustryTrendSegmentDTO publicSegment = segment(dto, "PUBLIC");

        IndustryTrendPointDTO q2_2025 = point(publicSegment, "2025-Q2");
        assertEquals(175_000d, q2_2025.actualRevenueDkk());
        assertNull(q2_2025.actualToDateDkk());
        assertNull(q2_2025.weightedPipelineDkk());

        IndustryTrendPointDTO current = point(publicSegment, "2026-Q3");
        assertNull(current.actualRevenueDkk());
        assertEquals(30_000d, current.actualToDateDkk());
        assertNotNull(current.weightedPipelineDkk());

        IndustryTrendPointDTO future = point(publicSegment, "2026-Q4");
        assertNull(future.actualRevenueDkk());
        assertNull(future.actualToDateDkk());
    }

    @Test
    void budgetCoversAllQuartersAndPipelineOnlyForecast() {
        List<BudgetRow> budget = List.of(
                new BudgetRow("ENERGY", "c1", "Vatten", "202410", 80_000d),  // past ghost
                new BudgetRow("ENERGY", "c1", "Vatten", "202608", 60_000d),  // current quarter
                new BudgetRow("ENERGY", "c1", "Vatten", "202701", 40_000d)); // future
        List<PipelineRow> pipeline = List.of(
                new PipelineRow("ENERGY", "202609", 20_000d),
                new PipelineRow("ENERGY", "202702", 10_000d));

        IndustryRevenueTrendDTO dto = assemble(List.of(), budget, pipeline, Map.of());
        IndustryTrendSegmentDTO energy = segment(dto, "ENERGY");

        assertEquals(80_000d, point(energy, "2024-Q4").budgetRevenueDkk());
        assertNull(point(energy, "2024-Q4").weightedPipelineDkk());
        assertEquals(60_000d, point(energy, "2026-Q3").budgetRevenueDkk());
        assertEquals(20_000d, point(energy, "2026-Q3").weightedPipelineDkk());
        assertEquals(40_000d, point(energy, "2027-Q1").budgetRevenueDkk());
        assertEquals(10_000d, point(energy, "2027-Q1").weightedPipelineDkk());

        // Budget-only client appears in the drill-down with its forecast budget.
        assertEquals(1, energy.clients().size());
        IndustryTrendClientDTO client = energy.clients().get(0);
        assertEquals(100_000d, client.forecastBudgetDkk());
        assertEquals(0d, client.windowRevenueDkk());
    }

    @Test
    void clientFlagsShareAndSortAreComputed() {
        List<RevenueRow> revenue = List.of(
                // c1: steady, existed before window (first invoice 2019)
                new RevenueRow("PUBLIC", "c1", "Big Agency", "202601", 300_000d),
                new RevenueRow("PUBLIC", "c1", "Big Agency", "202604", 300_000d),
                // c2: new in window, quiet (nothing in 2026-Q1 / 2026-Q2)
                new RevenueRow("PUBLIC", "c2", "Newcomer", "202501", 100_000d),
                // unattributed bucket
                new RevenueRow("OTHER", null, null, "202601", 42_000d));
        Map<String, String> firstInvoice = Map.of("c1", "201903", "c2", "202501");

        IndustryRevenueTrendDTO dto = assemble(revenue, List.of(), List.of(), firstInvoice);
        IndustryTrendSegmentDTO publicSegment = segment(dto, "PUBLIC");

        assertEquals(2, publicSegment.clients().size());
        IndustryTrendClientDTO big = publicSegment.clients().get(0);
        IndustryTrendClientDTO newcomer = publicSegment.clients().get(1);

        assertEquals("Big Agency", big.clientName());
        assertEquals(600_000d, big.windowRevenueDkk());
        assertEquals(300_000d, big.latestFullQuarterRevenueDkk());
        assertEquals(600_000d / 700_000d * 100d, big.sharePercent(), 0.0001);
        assertFalse(big.isNew());
        assertFalse(big.isQuiet());

        assertTrue(newcomer.isNew());
        assertTrue(newcomer.isQuiet());
        assertEquals(0d, newcomer.latestFullQuarterRevenueDkk());

        IndustryTrendClientDTO unattributed = segment(dto, "OTHER").clients().get(0);
        assertNull(unattributed.clientUuid());
        assertEquals(IndustryRevenueTrendService.UNATTRIBUTED_CLIENT_NAME, unattributed.clientName());
        assertEquals(42_000d, unattributed.windowRevenueDkk());
    }

    @Test
    void unknownPipelineSectorFoldsIntoOther() {
        List<PipelineRow> pipeline = List.of(new PipelineRow("", "202610", 5_000d));

        IndustryRevenueTrendDTO dto = assemble(List.of(), List.of(), pipeline, Map.of());

        assertEquals(5_000d, point(segment(dto, "OTHER"), "2026-Q4").weightedPipelineDkk());
    }

    @Test
    void zeroSumClientsAreDropped() {
        List<RevenueRow> revenue = List.of(
                new RevenueRow("HEALTH", "c9", "Cancelled Co", "202501", 10_000d),
                new RevenueRow("HEALTH", "c9", "Cancelled Co", "202502", -10_000d));

        IndustryRevenueTrendDTO dto = assemble(revenue, List.of(), List.of(), Map.of());

        assertTrue(segment(dto, "HEALTH").clients().isEmpty());
        // The quarter aggregate still nets to zero rather than disappearing.
        assertEquals(0d, point(segment(dto, "HEALTH"), "2025-Q1").actualRevenueDkk());
    }
}
