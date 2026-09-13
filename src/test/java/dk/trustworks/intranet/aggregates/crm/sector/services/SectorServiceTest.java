package dk.trustworks.intranet.aggregates.crm.sector.services;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanRag;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanStatus;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorPlanRefDTO;
import dk.trustworks.intranet.aggregates.crm.sector.model.SectorPlan;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules behind every sector roll-up (sectors spec §3.4, §7). Fast tier: no Quarkus
 * boot, no database. The whole-table queries are exercised against the local database
 * during verification; what is locked here is the arithmetic each card is assembled from.
 */
class SectorServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    // ------------------------------------------------------------------------
    // Segments
    // ------------------------------------------------------------------------

    @Test
    void sixSectorsInTheIndustriesTabOrder() {
        assertEquals(List.of(ClientSegment.PUBLIC, ClientSegment.ENERGY, ClientSegment.HEALTH,
                ClientSegment.FINANCIAL, ClientSegment.EDUCATION, ClientSegment.OTHER), SectorService.SECTOR_ORDER);
        assertEquals(ClientSegment.values().length, SectorService.SECTOR_ORDER.size(),
                "every segment must have a card — a new enum value needs a place in the order");
    }

    @Test
    void segmentsParseCaseInsensitivelyAndAnUnknownOneIs404() {
        assertEquals(ClientSegment.PUBLIC, SectorService.parseSegment("public"));
        assertEquals(ClientSegment.HEALTH, SectorService.parseSegment(" Health "));
        WebApplicationException unknown = assertThrows(WebApplicationException.class,
                () -> SectorService.parseSegment("RETAIL"));
        assertEquals(404, unknown.getResponse().getStatus());
        WebApplicationException blank = assertThrows(WebApplicationException.class,
                () -> SectorService.parseSegment("  "));
        assertEquals(400, blank.getResponse().getStatus());
    }

    /** 139 of 272 clients carry no segment or OTHER; a null must land in OTHER, not vanish. */
    @Test
    void aClientWithNoSegmentIsInOther() {
        Client client = new Client();
        client.setSegment(null);
        assertEquals(ClientSegment.OTHER, SectorService.segmentOf(client));
        client.setSegment(ClientSegment.ENERGY);
        assertEquals(ClientSegment.ENERGY, SectorService.segmentOf(client));
        assertEquals(ClientSegment.OTHER, SectorService.segmentOf(null));
    }

    // ------------------------------------------------------------------------
    // The financial year — 1 July to 30 June, named by its start year
    // ------------------------------------------------------------------------

    @Test
    void fiscalYearStartsInJulyAndIsNamedByItsStartYear() {
        assertEquals(2026, SectorService.fiscalYearOf(LocalDate.of(2026, 9, 13)));
        assertEquals(2026, SectorService.fiscalYearOf(LocalDate.of(2026, 7, 1)));
        assertEquals(2025, SectorService.fiscalYearOf(LocalDate.of(2026, 6, 30)));
        assertEquals(2025, SectorService.fiscalYearOf(LocalDate.of(2026, 1, 15)));
    }

    // ------------------------------------------------------------------------
    // Plan coverage — 45 and 90 days, the same thresholds the frontend carries
    // ------------------------------------------------------------------------

    @Test
    void coverageBucketsAtFortyFiveAndNinetyDays() {
        assertEquals(SectorService.COVERAGE_MISSING, SectorService.coverageBucket(false, null, TODAY));
        assertEquals(SectorService.COVERAGE_MISSING, SectorService.coverageBucket(false, TODAY, TODAY),
                "a plan that was never started is missing whatever date it carries");
        assertEquals(SectorService.COVERAGE_MISSING, SectorService.coverageBucket(true, null, TODAY));
        assertEquals(SectorService.COVERAGE_OK, SectorService.coverageBucket(true, TODAY.minusDays(45), TODAY));
        assertEquals(SectorService.COVERAGE_STALE, SectorService.coverageBucket(true, TODAY.minusDays(46), TODAY));
        assertEquals(SectorService.COVERAGE_STALE, SectorService.coverageBucket(true, TODAY.minusDays(90), TODAY));
        assertEquals(SectorService.COVERAGE_OVERDUE, SectorService.coverageBucket(true, TODAY.minusDays(91), TODAY));
    }

    @Test
    void anAccountIsQuietAfterNinetyDaysWithoutActivityOrWhenNeverSeen() {
        assertTrue(SectorService.isQuiet(null, TODAY));
        assertTrue(SectorService.isQuiet(TODAY.minusDays(91), TODAY));
        assertFalse(SectorService.isQuiet(TODAY.minusDays(90), TODAY));
        assertFalse(SectorService.isQuiet(TODAY, TODAY));
    }

    // ------------------------------------------------------------------------
    // The plan chip
    // ------------------------------------------------------------------------

    @Test
    void aSectorWithNoPlanReadsAsNotStartedRatherThanAsAnError() {
        SectorPlanRefDTO ref = SectorService.planRef(null);
        assertFalse(ref.started());
        assertNull(ref.rag());
        assertNull(ref.updatedAt());
        assertEquals(1, ref.version());
    }

    @Test
    void thePlanChipCarriesTheColourAndTheDayItWasLastConfirmed() {
        SectorPlan plan = new SectorPlan();
        plan.setSegment(ClientSegment.PUBLIC);
        plan.setStatus(PlanStatus.ACTIVE);
        plan.setVersion(3);
        plan.setHealthRag(PlanRag.AMBER);
        plan.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 14, 30));
        plan.setNextReview(LocalDate.of(2026, 12, 1));

        SectorPlanRefDTO ref = SectorService.planRef(plan);
        assertTrue(ref.started());
        assertEquals("AMBER", ref.rag());
        assertEquals(LocalDate.of(2026, 9, 1), ref.updatedAt());
        assertEquals(LocalDate.of(2026, 12, 1), ref.nextReview());
        assertEquals(3, ref.version());
    }

    /** Pinned: the sector card and the account KPI must agree on the thresholds. */
    @Test
    void thresholdsMatchTheSpec() {
        assertEquals(45, SectorService.PLAN_STALE_AFTER_DAYS);
        assertEquals(90, SectorService.PLAN_OVERDUE_AFTER_DAYS);
        assertEquals(90, SectorService.QUIET_AFTER_DAYS);
        assertEquals(90, SectorService.CONTRACT_EXPIRING_WITHIN_DAYS);
    }
}
