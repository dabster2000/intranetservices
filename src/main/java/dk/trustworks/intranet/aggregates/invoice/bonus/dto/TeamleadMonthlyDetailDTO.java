package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Read-only month-by-month drill-down of the utilization inputs that feed a team's teamlead bonus.
 * Each month mirrors exactly the population the bonus math consumes (dated MEMBER teamroles,
 * {@code CONSULTANT}/{@code ACTIVE} fact rows, leader-excluded) so an admin can reconcile the
 * collapsed team figures on the dashboard 1:1 against the per-member rows here.
 *
 * <p>Only the fiscal year's <em>considered</em> months are returned (completed months up to the
 * previous month), i.e. the same set the pool/points math averages over. Utilization ratios reuse
 * the dashboard convention (billable / available, rounded to 4 decimals) and are {@code null} when
 * the available-hours denominator is zero.</p>
 */
@Schema(name = "TeamleadMonthlyDetail",
        description = "Per-month utilization drill-down of the teamlead bonus inputs for a team")
public record TeamleadMonthlyDetailDTO(
        @Schema(description = "Team UUID") String teamUuid,
        @Schema(description = "Team name") String teamName,
        @Schema(description = "Fiscal year (starting year, e.g. 2025 for 2025-07-01..2026-06-30)") int fiscalYear,
        @Schema(description = "One entry per considered fiscal-year month, ordered by monthKey ascending")
        List<MonthDetail> months
) {

    /** One considered month of team-utilization inputs plus the month's leader. */
    @Schema(name = "TeamleadMonthDetail", description = "A single month's team utilization inputs")
    public record MonthDetail(
            @Schema(description = "Month key in YYYYMM format, e.g. 202507") String monthKey,
            @Schema(description = "Distinct active member count for the month") int memberCount,
            @Schema(description = "Team billable hours (Σ members)") double teamBillableHours,
            @Schema(description = "Team net available hours (Σ members)") double teamAvailableHours,
            @Schema(description = "Team utilization = billable / available; null when available is 0")
            Double teamUtilizationPct,
            @Schema(description = "UUID of the leader holding the LEADER role most of the month; null when none")
            String leaderUseruuid,
            @Schema(description = "Full name of the month's leader; null when none") String leaderName,
            @Schema(description = "Leader's own registered revenue for the month (DKK, CONSULTANT/ACTIVE)")
            double leaderRegisteredRevenueDkk,
            @Schema(description = "The resolved month-leader is excluded from this FY's teamlead bonus")
            boolean leaderExcluded,
            @Schema(description = "Per-member utilization rows that sum to the team totals")
            List<MemberDetail> members
    ) {}

    /** One member's utilization within a month (same population as the team total, split per user). */
    @Schema(name = "TeamleadMemberDetail", description = "A single member's utilization within a month")
    public record MemberDetail(
            @Schema(description = "Member user UUID") String useruuid,
            @Schema(description = "Member full name") String name,
            @Schema(description = "Member billable hours (ACTIVE rows only)") double billableHours,
            @Schema(description = "Member net available hours (ACTIVE rows only)") double availableHours,
            @Schema(description = "Member utilization = billable / available; null when available is 0")
            Double utilizationPct,
            // Editable-calculation-source fields (spec §2):
            @Schema(description = "null | FULL_LEAVE | PARTIAL_LEAVE") String leaveStatus,
            @Schema(description = "Dominant leave type (MATERNITY_LEAVE|PAID_LEAVE|NON_PAY_LEAVE), else null")
            String leaveType,
            @Schema(description = "ACTIVE-status calendar-day row count in the month") int activeDays,
            @Schema(description = "Leave-status calendar-day row count in the month") int leaveDays,
            @Schema(description = "Effective inclusion after default + override") boolean includedInCalculation,
            @Schema(description = "An override row exists for this (team, user, month)") boolean overridden,
            @Schema(description = "Override note (null when no override)") String overrideNote
    ) {}
}
