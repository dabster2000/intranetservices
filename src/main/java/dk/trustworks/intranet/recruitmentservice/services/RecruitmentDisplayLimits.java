package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * How many rows the recruitment read models serve before they roll up, read
 * from {@code app_settings} (category {@code recruitment}, seeded by V524)
 * on every call — the {@link RecruitmentTunables} contract.
 *
 * <p>Sibling of {@link RecruitmentSlaThresholds}: that one answers "how long
 * before we chase", this one answers "how much before we stop listing".
 * Kept apart because they are tuned for different reasons and by different
 * instincts — cadence is a process decision, list length is a screen
 * decision — but both surface in the same admin "Timing &amp; cadence" card
 * so nobody has to know that.
 *
 * <p>These were compiled constants scattered across three classes until
 * 2026-08-22 ({@code RecruitmentLandingService.FEED_SIZE},
 * {@code UPCOMING_INTERVIEWS_SIZE}, {@code SlackAppHomeViews.MAX_ROWS_PER_SECTION}).
 * Changing how long a list is meant a deployment, and the landing page and
 * Slack App Home each carried their own idea of "5".
 */
@ApplicationScoped
public class RecruitmentDisplayLimits {

    static final String TASK_ROWS_KEY = "recruitment.ui.task-rows";
    static final String ACTIVITY_ROWS_KEY = "recruitment.ui.activity-rows";
    static final String UPCOMING_INTERVIEW_ROWS_KEY = "recruitment.ui.upcoming-interview-rows";

    static final int DEFAULT_TASK_ROWS = 5;
    static final int DEFAULT_ACTIVITY_ROWS = 15;
    static final int DEFAULT_UPCOMING_INTERVIEW_ROWS = 5;

    /**
     * Raw events fetched per activity row served. Visibility filtering drops
     * rows after the fetch (CIRCLE events outside the viewer's circles,
     * partner-track candidates), so the fetch must over-read or a viewer with
     * a narrow slice gets a short feed. 4× reproduces the pre-2026-08-22
     * 15 → 60 ratio.
     */
    static final int FEED_OVERFETCH_FACTOR = 4;

    @Inject
    AppSettingService appSettingService;

    /**
     * Idle-candidate rows the landing "My tasks" card shows before the rest
     * collapse behind "Show N more", and rows per Slack App Home section.
     * One number for both — it is the same question on two screens, and two
     * answers would mean the same person's list stops in two places.
     */
    public int taskRows() {
        return RecruitmentTunables.positiveInt(appSettingService, TASK_ROWS_KEY, DEFAULT_TASK_ROWS);
    }

    /** Rows served in the landing activity feed. */
    public int activityRows() {
        return RecruitmentTunables.positiveInt(appSettingService, ACTIVITY_ROWS_KEY,
                DEFAULT_ACTIVITY_ROWS);
    }

    /** Raw events read before visibility filtering, derived from {@link #activityRows()}. */
    public int activityFetchRows() {
        return activityRows() * FEED_OVERFETCH_FACTOR;
    }

    /** The viewer's own upcoming interviews served on the landing page. */
    public int upcomingInterviewRows() {
        return RecruitmentTunables.positiveInt(appSettingService, UPCOMING_INTERVIEW_ROWS_KEY,
                DEFAULT_UPCOMING_INTERVIEW_ROWS);
    }
}
