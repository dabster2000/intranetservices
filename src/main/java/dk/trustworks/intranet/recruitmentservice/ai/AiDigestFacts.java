package dk.trustworks.intranet.recruitmentservice.ai;

import java.util.List;

/**
 * The complete model input of the two P24 AI digests (AI spec §5.5, plan
 * §P24) — <b>the PII boundary by type system</b>: every field is a number
 * or an enum-code/period-key string read from the P20 reporting
 * projections ({@code recruitment_fact_weekly} / {@code
 * recruitment_fact_monthly}), whose schemas have no column that could
 * hold a name or free text. There is deliberately no field that could
 * carry a candidate, a per-candidate row, or prose — the plan's hard
 * input rule ("prompts constructed exclusively from the projection's
 * numeric/enum fields") is a compile-time property of this record, and
 * {@code AiDigestServiceTest} mirrors it with a sentinel fixture.
 * <p>
 * The one input that cannot come from the event projections is
 * open-position coverage (current state, not a flow — plan §P24 names it
 * anyway): it enters as bare per-track <em>counts</em> from
 * {@code recruitment_positions}, preserving the rule's intent.
 *
 * <h3>Two windows, deliberately</h3>
 * The funnel digest is titled with an ISO week, so {@link
 * WeeklyFunnel#week()} carries that week's real numbers (from V523) and
 * is what the Slack headline and funnel table report. {@link
 * WeeklyFunnel#monthlyApplications()} carries the surrounding months (from
 * V449) purely as trend context for the chart. Before this split the
 * digest had only the monthly grain available, so a message headed
 * "week 34" reported a four-month window and its prose said "I denne
 * måned" — the two windows are now separate fields precisely so neither
 * the renderer nor the prompt can conflate them again.
 *
 * @param windowFrom first month of the trend window ({@code YYYY-MM})
 * @param windowTo   last month of the trend window ({@code YYYY-MM})
 */
public record AiDigestFacts(
        String windowFrom,
        String windowTo,
        WeeklyFunnel weeklyFunnel,
        RejectionPatterns rejectionPatterns) {

    /** Inputs of the weekly funnel narrative (null on the quarterly digest). */
    public record WeeklyFunnel(
            FunnelWindow week,
            FunnelWindow previousWeek,
            List<MonthCount> monthlyApplications,
            List<CodeCount> openPositionsByTrack) {

        /** Total open positions across all tracks. */
        public long openPositions() {
            return openPositionsByTrack.stream().mapToLong(CodeCount::count).sum();
        }
    }

    /**
     * One bounded window of funnel flow. Used twice — the reported ISO
     * week and the week before it, so every headline number can carry a
     * delta instead of standing alone with no way to tell up from down.
     *
     * @param from inclusive first day ({@code YYYY-MM-DD})
     * @param to   inclusive last day ({@code YYYY-MM-DD})
     */
    public record FunnelWindow(
            String from,
            String to,
            List<CodeCount> applicationsBySource,
            List<StageMove> stageMoves,
            List<StageDays> timeInStage,
            List<CodeCount> terminalsByOutcome,
            long hires,
            long scorecardsSubmitted,
            List<CodeCount> nudgesByType) {

        /** An empty window — what a quiet week legitimately looks like. */
        public static FunnelWindow empty(String from, String to) {
            return new FunnelWindow(from, to, List.of(), List.of(), List.of(), List.of(),
                    0L, 0L, List.of());
        }

        public long applicationTotal() {
            return applicationsBySource.stream().mapToLong(CodeCount::count).sum();
        }

        public long stageMoveTotal() {
            return stageMoves.stream().mapToLong(StageMove::count).sum();
        }

        public long terminalTotal() {
            return terminalsByOutcome.stream().mapToLong(CodeCount::count).sum();
        }

        public long nudgeTotal() {
            return nudgesByType.stream().mapToLong(CodeCount::count).sum();
        }
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

    /** One (month → count) point of the trend series; month is {@code YYYY-MM}. */
    public record MonthCount(String month, long count) {
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
