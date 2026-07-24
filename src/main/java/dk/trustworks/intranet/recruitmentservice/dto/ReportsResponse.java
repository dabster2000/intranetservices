package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * The full {@code GET /recruitment/reports} bundle (P20, spec §6.1): every
 * widget on {@code /recruitment/reports} in one call, all computed from the
 * {@code recruitment_fact_monthly} projection.
 * <p>
 * <b>Partner-track k-safety by construction (plan §P20):</b> the response
 * contains no per-position rows — partner-track activity is only ever
 * present inside fully aggregated counts. {@code userUuid} fields are
 * EMPLOYEE uuids (interviewers, referrers), never candidates.
 *
 * @param from             inclusive period start, {@code YYYY-MM}
 * @param to               inclusive period end, {@code YYYY-MM}
 * @param projectionEmpty  true when the projection holds no rows at all —
 *                         the page shows a "not built yet" callout instead
 *                         of all-zero charts
 * @param watermark        last event seq folded into the projection
 * @param streamHead       current head of the event stream
 */
public record ReportsResponse(
        String from,
        String to,
        boolean projectionEmpty,
        long watermark,
        long streamHead,
        List<SourceMixRow> sourceMix,
        List<HireRow> hires,
        List<StageMoveRow> funnel,
        List<TerminalRow> terminals,
        List<TimeInStageRow> timeInStage,
        List<InterviewerLoadRow> interviewerLoad,
        List<ReferralLeaderboardRow> referralLeaderboard,
        GdprTiles gdpr,
        List<AdoptionRow> adoption
) {

    /** Candidates + applications per month per source ({@code ''} source = unknown/legacy). */
    public record SourceMixRow(String month, String source, long candidates, long applications) {
    }

    /** Hires per month per source. */
    public record HireRow(String month, String source, long count) {
    }

    /** Aggregated stage transitions over the period (direction: FORWARD | BACK | ...). */
    public record StageMoveRow(String stageFrom, String stageTo, String direction, long count) {
    }

    /** Terminal outcomes per stage (outcome: REJECTED | WITHDRAWN | RETURNED_TO_POOL; reason set for rejections). */
    public record TerminalRow(String stage, String outcome, String reason, long count) {
    }

    /** Accumulated dwell time per stage; average days = sumDays / count. */
    public record TimeInStageRow(String stage, double sumDays, long count) {
    }

    /** Scorecards submitted (≈ interviews sat) per interviewer per month. */
    public record InterviewerLoadRow(String userUuid, String month, long count) {
    }

    /** Referral funnel per referring employee. */
    public record ReferralLeaderboardRow(String userUuid, long submitted, long converted, long hired) {
    }

    /** GDPR compliance counters for the period. */
    public record GdprTiles(
            long art14NoticesSent,
            long consentsGranted,
            long consentsWithdrawn,
            long consentsExpired,
            long anonymizedAuto,
            long anonymizedOnRequest,
            long dsarsReceived,
            long dsarsExported
    ) {
    }

    /** Slack-vs-web split per month per action (SCORECARD | REFERRAL | TRIAGE). */
    public record AdoptionRow(String month, String action, long web, long slack) {
    }
}
