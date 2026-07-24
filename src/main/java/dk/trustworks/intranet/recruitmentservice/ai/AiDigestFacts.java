package dk.trustworks.intranet.recruitmentservice.ai;

import java.util.List;

/**
 * The complete model input of the two P24 AI digests (AI spec §5.5, plan
 * §P24) — <b>the PII boundary by type system</b>: every field is a number
 * or an enum-code/period-key string read from the P20 reporting projection
 * ({@code recruitment_fact_monthly}), whose schema has no column that
 * could hold a name or free text. There is deliberately no field that
 * could carry a candidate, a per-candidate row, or prose — the plan's
 * hard input rule ("prompts constructed exclusively from the projection's
 * numeric/enum fields") is a compile-time property of this record, and
 * {@code AiDigestServiceTest} mirrors it with a sentinel fixture.
 * <p>
 * The one input that cannot come from the monthly event projection is
 * open-position coverage (current state, not a flow — plan §P24 names it
 * anyway): it enters as bare per-track <em>counts</em> from
 * {@code recruitment_positions}, preserving the rule's intent.
 *
 * @param windowFrom first month of the aggregate window ({@code YYYY-MM})
 * @param windowTo   last month of the aggregate window ({@code YYYY-MM})
 */
public record AiDigestFacts(
        String windowFrom,
        String windowTo,
        WeeklyFunnel weeklyFunnel,
        RejectionPatterns rejectionPatterns) {

    /** Inputs of the weekly funnel narrative (null on the quarterly digest). */
    public record WeeklyFunnel(
            List<MonthCodeCount> applicationsPerSource,
            List<StageMove> stageMoves,
            List<StageDays> timeInStage,
            List<CodeCount> terminalsByOutcome,
            long hires,
            long scorecardsSubmitted,
            List<CodeCount> nudgesByType,
            List<CodeCount> openPositionsByTrack) {
    }

    /** Inputs of the quarterly rejection-pattern narrative (null on the weekly). */
    public record RejectionPatterns(
            String fiscalQuarterLabel,
            List<CodeCount> rejectionsByReason,
            List<CodeCount> rejectionsByStage,
            List<SourceRejectionRate> rejectionsBySource,
            long totalRejections,
            long totalApplications) {
    }

    /** One (enum code → count) aggregate. */
    public record CodeCount(String code, long count) {
    }

    /** One (month, enum code → count) aggregate; month is {@code YYYY-MM}. */
    public record MonthCodeCount(String month, String code, long count) {
    }

    /** One stage transition aggregate; all three codes are closed enums. */
    public record StageMove(String fromStage, String toStage, String direction, long count) {
    }

    /** Average fractional days spent in a stage across the window's moves. */
    public record StageDays(String stage, double avgDays, long moves) {
    }

    /** Per-source rejection pressure: rejected vs. applications received. */
    public record SourceRejectionRate(String source, long rejected, long applications) {
    }
}
