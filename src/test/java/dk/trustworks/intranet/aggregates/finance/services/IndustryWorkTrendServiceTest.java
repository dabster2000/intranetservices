package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryEngagementPointDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryEngagementSegmentDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryEngagementTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryRatePointDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryRateSegmentDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryRateTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryServiceLinePointDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryServiceLineSegmentDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryServiceLineTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryTrendQuarterDTO;
import dk.trustworks.intranet.aggregates.finance.services.IndustryWorkTrendService.ActiveMonthRow;
import dk.trustworks.intranet.aggregates.finance.services.IndustryWorkTrendService.ActualsWindow;
import dk.trustworks.intranet.aggregates.finance.services.IndustryWorkTrendService.Episode;
import dk.trustworks.intranet.aggregates.finance.services.IndustryWorkTrendService.RateRow;
import dk.trustworks.intranet.aggregates.finance.services.IndustryWorkTrendService.ServiceLineRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.aggregates.finance.services.IndustryWorkTrendService.monthNum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for the pure window/assembly logic behind the Industries tab
 * endpoints (industry-rate-trend, industry-engagement-trend,
 * industry-service-line-trend).
 */
class IndustryWorkTrendServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 25);

    private static ActiveMonthRow month(String segment, String user, String client, int year, int month) {
        return new ActiveMonthRow(segment, user, "Consultant " + user, client, "Client " + client,
                year * 12 + month);
    }

    // -------------------------------------------------------------------------
    // Window
    // -------------------------------------------------------------------------

    @Test
    void windowSpansTwelveActualQuarters() {
        ActualsWindow window = ActualsWindow.of(AS_OF);

        assertEquals(LocalDate.of(2023, 7, 1), window.windowStart());
        assertEquals(LocalDate.of(2026, 7, 1), window.currentQuarterStart());
        assertEquals("202307", window.windowStartMonthKey());
        assertEquals("202606", window.windowEndMonthKey());
        assertEquals("2026-Q2", window.latestFullQuarterKey());

        List<IndustryTrendQuarterDTO> quarters = window.quarterMetas();
        assertEquals(12, quarters.size());
        assertEquals("2023-Q3", quarters.get(0).quarterKey());
        assertEquals("2026-Q2", quarters.get(11).quarterKey());
        for (IndustryTrendQuarterDTO quarter : quarters) {
            assertEquals(IndustryTrendQuarterDTO.PHASE_ACTUAL, quarter.phase());
        }
        assertEquals("FY23/24", quarters.get(0).fiscalYearLabel());
        assertNull(quarters.get(1).fiscalYearLabel());
        assertEquals("FY24/25", quarters.get(4).fiscalYearLabel());
        assertEquals("FY25/26", quarters.get(8).fiscalYearLabel());
    }

    // -------------------------------------------------------------------------
    // Episode detection
    // -------------------------------------------------------------------------

    @Test
    void consecutiveMonthsFormOneEpisode() {
        List<Episode> episodes = IndustryWorkTrendService.detectEpisodes(List.of(
                month("PUBLIC", "u1", "c1", 2025, 1),
                month("PUBLIC", "u1", "c1", 2025, 2),
                month("PUBLIC", "u1", "c1", 2025, 3)));

        assertEquals(1, episodes.size());
        assertEquals(2025 * 12 + 1, episodes.get(0).firstMonthNum());
        assertEquals(2025 * 12 + 3, episodes.get(0).lastMonthNum());
    }

    @Test
    void vacationGapsUpToTwoMonthsAreBridged() {
        // Active Jan–Jun, silent Jul+Aug (summer vacation), active again Sep.
        List<Episode> episodes = IndustryWorkTrendService.detectEpisodes(List.of(
                month("PUBLIC", "u1", "c1", 2025, 1),
                month("PUBLIC", "u1", "c1", 2025, 6),
                month("PUBLIC", "u1", "c1", 2025, 2),
                month("PUBLIC", "u1", "c1", 2025, 3),
                month("PUBLIC", "u1", "c1", 2025, 4),
                month("PUBLIC", "u1", "c1", 2025, 5),
                month("PUBLIC", "u1", "c1", 2025, 9)));

        assertEquals(1, episodes.size());
        assertEquals(2025 * 12 + 1, episodes.get(0).firstMonthNum());
        assertEquals(2025 * 12 + 9, episodes.get(0).lastMonthNum());
        // Bridged months count toward the running length: Jan..Sep = 9 months.
        assertEquals(9, episodes.get(0).runningMonthsThrough(2025 * 12 + 12));
    }

    @Test
    void threeSilentMonthsSplitIntoTwoEpisodes() {
        // Active Jan–Mar, silent Apr–Jun, active again Jul → re-engagement.
        List<Episode> episodes = IndustryWorkTrendService.detectEpisodes(List.of(
                month("ENERGY", "u1", "c1", 2025, 1),
                month("ENERGY", "u1", "c1", 2025, 2),
                month("ENERGY", "u1", "c1", 2025, 3),
                month("ENERGY", "u1", "c1", 2025, 7)));

        assertEquals(2, episodes.size());
        assertEquals(2025 * 12 + 3, episodes.get(0).lastMonthNum());
        assertEquals(2025 * 12 + 7, episodes.get(1).firstMonthNum());
    }

    @Test
    void pairsAreTrackedIndependently() {
        List<Episode> episodes = IndustryWorkTrendService.detectEpisodes(List.of(
                month("PUBLIC", "u1", "c1", 2025, 1),
                month("PUBLIC", "u2", "c1", 2025, 1),
                month("HEALTH", "u1", "c2", 2025, 1)));

        assertEquals(3, episodes.size());
    }

    // -------------------------------------------------------------------------
    // Engagement assembly
    // -------------------------------------------------------------------------

    @Test
    void engagementSeriesMeasuresRunningLengthPerQuarter() {
        ActualsWindow window = ActualsWindow.of(AS_OF);
        // One engagement running Jan 2025 → Jun 2026 (18 months).
        List<ActiveMonthRow> rows = new java.util.ArrayList<>();
        for (int m = monthNum(LocalDate.of(2025, 1, 1)); m <= monthNum(LocalDate.of(2026, 6, 1)); m++) {
            rows.add(new ActiveMonthRow("PUBLIC", "u1", "Anna", "c1", "Client A", m));
        }

        IndustryEngagementTrendDTO dto = IndustryWorkTrendService.assembleEngagements(window, rows);

        assertEquals(12, dto.quarters().size());
        assertEquals("2026-Q2", dto.latestFullQuarterKey());
        assertEquals(8d, dto.activeMonthMinHours());
        assertEquals(2, dto.bridgeMonths());

        IndustryEngagementSegmentDTO publicSegment = dto.industries().stream()
                .filter(s -> s.segmentCode().equals("PUBLIC")).findFirst().orElseThrow();

        // 2024-Q4 (index 5): engagement has not started yet.
        IndustryEngagementPointDTO q42024 = publicSegment.series().get(5);
        assertEquals(0, q42024.activeEngagements());
        assertNull(q42024.avgRunningMonths());

        // 2025-Q1 (index 6): first quarter — running length Jan..Mar = 3 months.
        IndustryEngagementPointDTO q12025 = publicSegment.series().get(6);
        assertEquals(1, q12025.activeEngagements());
        assertEquals(1, q12025.startedEngagements());
        assertEquals(3d, q12025.avgRunningMonths());

        // 2026-Q2 (index 11): running length Jan 2025..Jun 2026 = 18 months.
        IndustryEngagementPointDTO q22026 = publicSegment.series().get(11);
        assertEquals(1, q22026.activeEngagements());
        assertEquals(18d, q22026.avgRunningMonths());
        // Last active month is June; only Jul + partial Aug silence has elapsed by
        // the as-of date, so the engagement is not yet counted as ended.
        assertEquals(0, q22026.endedEngagements());

        assertEquals(1, publicSegment.engagements().size());
        assertEquals("202501", publicSegment.engagements().get(0).startMonthKey());
        assertEquals(18, publicSegment.engagements().get(0).runningMonths());
        assertTrue(publicSegment.engagements().get(0).active());
    }

    @Test
    void longSilenceMarksEngagementEnded() {
        ActualsWindow window = ActualsWindow.of(AS_OF);
        // Engagement Jan 2025 → Mar 2025, silent ever since (well past the bridge).
        List<ActiveMonthRow> rows = List.of(
                month("HEALTH", "u1", "c1", 2025, 1),
                month("HEALTH", "u1", "c1", 2025, 2),
                month("HEALTH", "u1", "c1", 2025, 3));

        IndustryEngagementTrendDTO dto = IndustryWorkTrendService.assembleEngagements(window, rows);
        IndustryEngagementSegmentDTO health = dto.industries().stream()
                .filter(s -> s.segmentCode().equals("HEALTH")).findFirst().orElseThrow();

        IndustryEngagementPointDTO q12025 = health.series().get(6);
        assertEquals(1, q12025.activeEngagements());
        assertEquals(1, q12025.endedEngagements());

        // Not active in the latest full quarter → not in the drill-down list.
        assertTrue(health.engagements().isEmpty());

        // Later quarters no longer count it as active.
        IndustryEngagementPointDTO q22026 = health.series().get(11);
        assertEquals(0, q22026.activeEngagements());
    }

    // -------------------------------------------------------------------------
    // Rate assembly
    // -------------------------------------------------------------------------

    @Test
    void rateSeriesIsHoursWeighted() {
        ActualsWindow window = ActualsWindow.of(AS_OF);
        List<RateRow> rows = List.of(
                new RateRow("PUBLIC", "c1", "Client A", "2026-Q2", 100_000d, 100d),
                new RateRow("PUBLIC", "c2", "Client B", "2026-Q2", 390_000d, 300d),
                new RateRow("PUBLIC", "c1", "Client A", "2026-Q1", 120_000d, 100d));

        IndustryRateTrendDTO dto = IndustryWorkTrendService.assembleRates(window, rows);

        assertEquals(12, dto.quarters().size());
        IndustryRateSegmentDTO publicSegment = dto.industries().stream()
                .filter(s -> s.segmentCode().equals("PUBLIC")).findFirst().orElseThrow();

        // 2026-Q2 (index 11): (100000 + 390000) / (100 + 300) = 1225 DKK/h, 2 clients.
        IndustryRatePointDTO q2 = publicSegment.series().get(11);
        assertEquals(1225d, q2.avgRateDkk());
        assertEquals(400d, q2.hours());
        assertEquals(2, q2.clientCount());

        // 2026-Q1 (index 10): only Client A → 1200 DKK/h.
        IndustryRatePointDTO q1 = publicSegment.series().get(10);
        assertEquals(1200d, q1.avgRateDkk());
        assertEquals(1, q1.clientCount());

        // Quarters without hours carry null rates.
        assertNull(publicSegment.series().get(0).avgRateDkk());

        // Drill-down: Client B first (more window hours), latest-quarter rate present.
        assertEquals("Client B", publicSegment.clients().get(0).clientName());
        assertEquals(1300d, publicSegment.clients().get(0).latestQuarterAvgRateDkk());
        assertEquals("Client A", publicSegment.clients().get(1).clientName());
        assertEquals(200d, publicSegment.clients().get(1).windowHours());
        assertEquals(1100d, publicSegment.clients().get(1).windowAvgRateDkk());

        // Empty segments still serialize with a full, null-rated series.
        IndustryRateSegmentDTO energy = dto.industries().stream()
                .filter(s -> s.segmentCode().equals("ENERGY")).findFirst().orElseThrow();
        assertEquals(12, energy.series().size());
        assertTrue(energy.clients().isEmpty());
    }

    @Test
    void unknownSegmentsFoldIntoOther() {
        ActualsWindow window = ActualsWindow.of(AS_OF);
        List<RateRow> rows = List.of(
                new RateRow(null, "c1", "Client A", "2026-Q2", 50_000d, 50d),
                new RateRow("WEIRD", "c2", "Client B", "2026-Q2", 50_000d, 50d));

        IndustryRateTrendDTO dto = IndustryWorkTrendService.assembleRates(window, rows);
        IndustryRateSegmentDTO other = dto.industries().stream()
                .filter(s -> s.segmentCode().equals("OTHER")).findFirst().orElseThrow();

        assertEquals(1000d, other.series().get(11).avgRateDkk());
        assertEquals(2, other.series().get(11).clientCount());
    }

    // -------------------------------------------------------------------------
    // Service-line assembly
    // -------------------------------------------------------------------------

    @Test
    void serviceLineSeriesCountsDistinctClientsAndAverageLines() {
        ActualsWindow window = ActualsWindow.of(AS_OF);
        List<ServiceLineRow> rows = List.of(
                new ServiceLineRow("PUBLIC", "TECH", "c1", "Client A", "2026-Q2"),
                new ServiceLineRow("PUBLIC", "PM", "c1", "Client A", "2026-Q2"),
                new ServiceLineRow("PUBLIC", "TECH", "c2", "Client B", "2026-Q2"),
                new ServiceLineRow("PUBLIC", "TECH", "c1", "Client A", "2026-Q1"));

        IndustryServiceLineTrendDTO dto = IndustryWorkTrendService.assembleServiceLines(
                window, rows, Map.of("TECH", "Technology", "PM", "Project Managers"));

        assertEquals(2, dto.serviceLines().size());
        assertEquals("PM", dto.serviceLines().get(0).code());
        assertEquals("Project Managers", dto.serviceLines().get(0).displayName());
        assertEquals("Technology", dto.serviceLines().get(1).displayName());

        IndustryServiceLineSegmentDTO publicSegment = dto.industries().stream()
                .filter(s -> s.segmentCode().equals("PUBLIC")).findFirst().orElseThrow();

        // 2026-Q2: TECH used by 2 clients, PM by 1; avg lines per client = 3/2.
        IndustryServiceLinePointDTO q2 = publicSegment.series().get(11);
        assertEquals(2, q2.activeClients());
        assertEquals(2, q2.clientsByServiceLine().get("TECH"));
        assertEquals(1, q2.clientsByServiceLine().get("PM"));
        assertEquals(1.5d, q2.avgServiceLinesPerClient());

        // 2026-Q1: one client, one line.
        IndustryServiceLinePointDTO q1 = publicSegment.series().get(10);
        assertEquals(1, q1.activeClients());
        assertEquals(1.0d, q1.avgServiceLinesPerClient());

        // Latest-quarter drill-down carries client names per line.
        assertEquals(List.of("Client A", "Client B"), publicSegment.latestQuarterClients().get("TECH"));
        assertEquals(List.of("Client A"), publicSegment.latestQuarterClients().get("PM"));

        // Empty quarters have no counts and a null average.
        IndustryServiceLinePointDTO first = publicSegment.series().get(0);
        assertEquals(0, first.activeClients());
        assertTrue(first.clientsByServiceLine().isEmpty());
        assertNull(first.avgServiceLinesPerClient());

        // Unlisted practice codes fall back to the raw code.
        IndustryServiceLineTrendDTO fallback = IndustryWorkTrendService.assembleServiceLines(
                window, List.of(new ServiceLineRow("PUBLIC", "UD", "c1", "Client A", "2026-Q2")), Map.of());
        assertEquals("UD", fallback.serviceLines().get(0).displayName());
        assertFalse(fallback.serviceLines().isEmpty());
    }
}
