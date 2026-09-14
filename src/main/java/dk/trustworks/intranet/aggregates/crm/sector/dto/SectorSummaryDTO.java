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
 * @param accounts       Strategic / Active / Backlog counts, how many contacts the sector
 *                       holds, and how many Strategic/Active accounts have no owner
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

    /**
     * The four numbers on a sector card's accounts line: ★ n · n active · n backlog · n contacts.
     *
     * <p><b>Contacts are counted BESIDE the three bands, not inside Backlog.</b> A contact
     * is a company Intra knows and has never billed, with no open lead and no decided band
     * — nothing is expected of it, so counting it as Backlog made the backlog look like a
     * queue nobody was working when most of it was a phone book. The four sum to the
     * sector's client count.
     *
     * <p>{@code unowned} and the plan-coverage and quiet numbers deliberately exclude
     * contacts for the same reason: an account nobody is expected to own cannot be unowned.
     */
    public record AccountsByBandDTO(int strategic, int active, int backlog, int contacts, int unowned) {
    }

    public record PlanCoverageDTO(int ok, int stale, int overdue, int missing) {
    }

    public record TeamRefDTO(String uuid, String name) {
    }
}
