package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The role-aware landing aggregate (ATS plan §P17, spec §6.1
 * {@code /recruitment}): everything the landing page renders, computed per
 * caller from involvement in ONE request — the DoD's "single aggregated
 * endpoint, no request fan-out from the client". The {@code tasks} section
 * is also the future Slack App Home source (P23).
 *
 * <h3>Viewer shapes</h3>
 * <ul>
 *   <li>{@code RECRUITER} — recruiter tier (ADMIN/HR/CXO): the world.</li>
 *   <li>{@code INVOLVED} — owns/leads/reads at least one position
 *       (hiring owner, current team lead, current practice lead, circle
 *       member): their positions' slice.</li>
 *   <li>{@code INTERVIEWER} — no position involvement but at least one
 *       interview assignment: scorecard tasks + own interviews only.</li>
 *   <li>{@code EMPLOYEE} — no recruitment involvement at all: the client
 *       redirects to {@code /recruitment/refer} (spec §6.1).</li>
 * </ul>
 */
public record LandingResponse(
        String viewerShape,
        LandingKpis kpis,
        List<LandingTask> tasks,
        List<LandingPipeline> pipelines,
        List<LandingInterview> upcomingInterviews,
        List<LandingActivity> activity) {

    public static final String SHAPE_RECRUITER = "RECRUITER";
    public static final String SHAPE_INVOLVED = "INVOLVED";
    public static final String SHAPE_INTERVIEWER = "INTERVIEWER";
    public static final String SHAPE_EMPLOYEE = "EMPLOYEE";

    /** The KPI row, scoped to what the viewer can see. */
    public record LandingKpis(
            int openPositions,
            int activeCandidates,
            int interviewsNext7Days,
            int openTasks) {
    }

    /**
     * One actionable row in "My tasks", ordered by urgency server-side.
     * Per-item types carry the item's subjects; the two queue types
     * ({@code REFERRAL_TO_TRIAGE}, {@code EMAIL_REVIEW}) are single
     * aggregate rows carrying only {@code count}.
     */
    public record LandingTask(
            String type,
            String candidateUuid,
            String candidateName,
            String positionUuid,
            String positionTitle,
            String applicationUuid,
            String interviewUuid,
            Integer round,
            String stage,
            Long ageHours,
            LocalDateTime since,
            Integer count) {

        public static final String TYPE_OVERDUE_SCORECARD = "OVERDUE_SCORECARD";
        public static final String TYPE_PENDING_DECISION = "PENDING_DECISION";
        public static final String TYPE_EMAIL_REVIEW = "EMAIL_REVIEW";
        public static final String TYPE_REFERRAL_TO_TRIAGE = "REFERRAL_TO_TRIAGE";
        public static final String TYPE_IDLE_CANDIDATE = "IDLE_CANDIDATE";
    }

    /** One row of "Your pipelines": a position with its per-stage counts. */
    public record LandingPipeline(
            String positionUuid,
            String title,
            String practiceName,
            String hiringTrack,
            String demandRag,
            int openCount,
            int idleCount,
            List<LandingStageCount> stageCounts) {
    }

    /** Open-application count for one stage-set entry (set order preserved). */
    public record LandingStageCount(String stage, int count) {
    }

    /** One of the viewer's own upcoming interviews (assignments only). */
    public record LandingInterview(
            String interviewUuid,
            String candidateUuid,
            String candidateName,
            String positionTitle,
            String kind,
            Integer round,
            LocalDateTime scheduledAt,
            String location,
            boolean scorecardRequired,
            boolean ownScorecardSubmitted) {
    }

    /**
     * One visibility-filtered activity-feed row — structural facts and
     * names only, never event pii (note text, email bodies and scorecard
     * notes stay on the profile timeline behind its own authz).
     */
    public record LandingActivity(
            long seq,
            String eventType,
            LocalDateTime occurredAt,
            String candidateUuid,
            String candidateName,
            String positionTitle,
            String actorType,
            String actorName) {
    }
}
