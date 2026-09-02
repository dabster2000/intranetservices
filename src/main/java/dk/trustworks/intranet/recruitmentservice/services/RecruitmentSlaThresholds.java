package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The SLA cadence thresholds, read from {@code app_settings} on every call
 * (the {@link RecruitmentFeatureFlag} idiom — the table is tiny, and no
 * cache means an admin edit takes effect on the next sweep without a
 * redeploy). The three P17 thresholds are seeded by V447 and the nudge cap
 * by V524; a missing or unparseable row falls back to the plan's defaults
 * (24 h / 7 d / 48 h / 2 nudges), never to "off" — the sweep's on/off switch
 * is {@code recruitment.interviews.enabled}, not these
 * ({@link RecruitmentTunables}).
 *
 * <p>Row counts live in the sibling {@link RecruitmentDisplayLimits}: this
 * class answers "how long before we chase", that one "how much before we
 * stop listing".
 *
 * <p>{@link #candidateIdleDays()} is the single definition of "too long" in
 * this module. It drives the SLA DM, the landing {@code IDLE_CANDIDATE} row
 * and its pipelines badge (both through {@link RecruitmentIdleRule}) and —
 * since 2026-08-22 — the pipeline board's idle chip, which until then carried
 * its own hard-coded 7 and therefore called a candidate idle three days later
 * than every other screen did.
 */
@ApplicationScoped
public class RecruitmentSlaThresholds {

    static final String SCORECARD_OVERDUE_HOURS_KEY = "recruitment.sla.scorecard-overdue-hours";
    static final String CANDIDATE_IDLE_DAYS_KEY = "recruitment.sla.candidate-idle-days";
    static final String DEBRIEF_STALLED_HOURS_KEY = "recruitment.sla.debrief-stalled-hours";
    static final String MAX_SCORECARD_NUDGES_KEY = "recruitment.sla.max-scorecard-nudges";
    static final String SCORECARD_PROMPT_MINUTES_KEY = "recruitment.sla.scorecard-prompt-minutes";
    static final String BRIEF_LEAD_DAYS_KEY = "recruitment.brief.lead-days";
    static final String EMAIL_REVIEW_STALE_HOURS_KEY = "recruitment.sla.email-review-stale-hours";

    static final int DEFAULT_SCORECARD_OVERDUE_HOURS = 24;
    static final int DEFAULT_CANDIDATE_IDLE_DAYS = 7;
    static final int DEFAULT_DEBRIEF_STALLED_HOURS = 48;
    static final int DEFAULT_MAX_SCORECARD_NUDGES = 2;
    static final int DEFAULT_SCORECARD_PROMPT_MINUTES = 20;
    static final int DEFAULT_BRIEF_LEAD_DAYS = 1;
    static final int DEFAULT_EMAIL_REVIEW_STALE_HOURS = 48;

    @Inject
    AppSettingService appSettingService;

    /** Hours after a round interview's time before the missing-scorecard DM. */
    public int scorecardOverdueHours() {
        return readPositiveInt(SCORECARD_OVERDUE_HOURS_KEY, DEFAULT_SCORECARD_OVERDUE_HOURS);
    }

    /**
     * Days an application may go without progress before it counts as idle —
     * the owner ping, the landing task row, the pipelines badge and the board
     * chip all measure against this one number.
     */
    public int candidateIdleDays() {
        return readPositiveInt(CANDIDATE_IDLE_DAYS_KEY, DEFAULT_CANDIDATE_IDLE_DAYS);
    }

    /** Hours a debrief-ready round may sit unactioned before the owner ping. */
    public int debriefStalledHours() {
        return readPositiveInt(DEBRIEF_STALLED_HOURS_KEY, DEFAULT_DEBRIEF_STALLED_HOURS);
    }

    /**
     * How many scorecard DMs one interviewer may get for one interview
     * (spec §8.4). A hard stop, not a cadence: past it the sweep goes quiet
     * for that pair forever, submitted or not, so the reminder can never
     * become the thing people mute.
     */
    public int maxScorecardNudges() {
        return readPositiveInt(MAX_SCORECARD_NUDGES_KEY, DEFAULT_MAX_SCORECARD_NUDGES);
    }

    /**
     * Minutes after a round interview actually ENDS (start plus its booked
     * duration) before the first scorecard ask. Deliberately short: measured
     * on production 2026-08-24, not one of 19 scorecards was submitted before
     * the meeting ended and the mean submission was 24.9 h after it, so the
     * first ask was always arriving long after the impression had faded.
     *
     * <p>It must never be zero-ish: the floor exists so a meeting that runs a
     * few minutes over does not get its interviewer pinged while they are
     * still in the room with the candidate — the same mistake the landing
     * page made before it started measuring from {@code endOf} rather than
     * from the start time.
     */
    public int scorecardPromptMinutes() {
        return readPositiveInt(SCORECARD_PROMPT_MINUTES_KEY, DEFAULT_SCORECARD_PROMPT_MINUTES);
    }

    /**
     * How many days before an interview the prep brief goes out — 1 means
     * the working day before. "Working day" is the point: the eve sweep
     * walks back over Saturdays and Sundays so a Monday interview briefs on
     * Friday. A brief delivered on a Sunday is a brief nobody reads, which
     * is the failure this whole change exists to fix.
     */
    /**
     * How long a candidate email may sit unapproved in the review queue
     * before the recruiter tier is nudged. 48 hours by default: the
     * acknowledgement letter promises an answer within four working days,
     * so two days is the point at which the queue is the reason that
     * promise is at risk rather than ordinary turnaround.
     * <p>
     * Deliberately NOT seeded by a migration — {@code readPositiveInt}
     * already answers the default, and an unpushed Flyway number is not
     * reserved (three collisions to date). Set the row only to override.
     */
    public int emailReviewStaleHours() {
        return readPositiveInt(EMAIL_REVIEW_STALE_HOURS_KEY, DEFAULT_EMAIL_REVIEW_STALE_HOURS);
    }

    public int briefLeadDays() {
        return readPositiveInt(BRIEF_LEAD_DAYS_KEY, DEFAULT_BRIEF_LEAD_DAYS);
    }

    private int readPositiveInt(String key, int defaultValue) {
        return RecruitmentTunables.positiveInt(appSettingService, key, defaultValue);
    }
}
