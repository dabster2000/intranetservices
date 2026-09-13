package dk.trustworks.intranet.aggregates.crm.sector.dto;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;

import java.util.List;

/**
 * One sector card on the Sectors tab (sectors spec §6.1). Always six rows, one per
 * segment, whether or not the segment has any client.
 *
 * <p>Every number is derived from the accounts in the sector; the only typed things are
 * the lead and the plan reference. {@code breakEven} and {@code target} are null unless the
 * caller holds a cost role — decided server-side, so the figure never crosses the wire.
 *
 * @param accounts       Strategic / Active / Backlog counts and how many Strategic/Active
 *                       accounts have no owner
 * @param quiet          Active/Strategic accounts with no activity for 90 days
 * @param fiscalYear     the FY the revenue figures are for, by its start year (FY2026 = Jul 2026 – Jun 2027)
 * @param planCoverage   Strategic/Active accounts by plan freshness: ok ≤ 45 d, stale 45–90 d,
 *                       overdue > 90 d, missing (no plan started)
 */
public record SectorSummaryDTO(
        String segment,
        String label,
        PersonDTO lead,
        AccountsByBandDTO accounts,
        int quiet,
        int fiscalYear,
        double fyRevenue,
        double fyRevenuePrevious,
        Double weightedRate,
        Double breakEven,
        Double target,
        int consultants,
        int openLeads,
        double weightedPipeline,
        int signalsWaiting,
        PlanCoverageDTO planCoverage,
        SectorPlanRefDTO plan,
        List<TeamRefDTO> gtmTeams) {

    public record AccountsByBandDTO(int strategic, int active, int backlog, int unowned) {
    }

    public record PlanCoverageDTO(int ok, int stale, int overdue, int missing) {
    }

    public record TeamRefDTO(String uuid, String name) {
    }
}
